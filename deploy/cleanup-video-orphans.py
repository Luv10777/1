#!/usr/bin/env python3
"""One-time, tenant-scoped video orphan cleanup. Dry run unless --apply is supplied."""
import argparse
import json
import os
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit

VIDEO_KEY = re.compile(
    r"t([1-9][0-9]*)/generated-video/([1-9][0-9]*)/"
    r"(?:retry-[1-9][0-9]*/)?(?:import-[0-9a-f-]{36}/)?output\.mp4\Z"
)


def emit(action, key, reason=None):
    print(json.dumps({"action": action, "key": key, "reason": reason}, ensure_ascii=False))


def eligible(cursor, tenant_id, workflow_id, key):
    # The caller holds a SHARE table lock on assets: no new reference can appear before DELETE.
    # The workflow row lock prevents retry/QA/cancel from changing eligibility concurrently.
    cursor.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_id),))
    cursor.execute("SELECT status, output_published_at, progress FROM video_workflows "
                   "WHERE id=%s AND tenant_id=%s FOR UPDATE", (workflow_id, tenant_id))
    workflow = cursor.fetchone()
    if workflow is None:
        return "workflow_missing"
    if workflow[0] not in ("FAILED", "CANCELLED") or workflow[1] is not None or workflow[2] == 100:
        return "workflow_active_or_published"
    # Global checks require BYPASSRLS; ANY Asset reference, including INVALID, protects the key.
    cursor.execute("SELECT EXISTS(SELECT 1 FROM assets WHERE storage_key=%s)", (key,))
    if cursor.fetchone()[0]:
        return "asset_referenced"
    cursor.execute("SELECT EXISTS(SELECT 1 FROM video_workflows WHERE output_storage_key=%s "
                   "AND (status='SUCCEEDED' OR output_published_at IS NOT NULL OR progress=100))", (key,))
    if cursor.fetchone()[0]:
        return "published_reference"
    cursor.execute("SELECT EXISTS(SELECT 1 FROM tasks WHERE type IN "
                   "('VIDEO_SUBMIT','VIDEO_POLL','VIDEO_IMPORT','VIDEO_QA') "
                   "AND tenant_id=%s AND payload->>'workflowId'=%s "
                   "AND status IN ('PENDING','RUNNING'))", (tenant_id, str(workflow_id)))
    return "task_active" if cursor.fetchone()[0] else None


def cleanup(connection, minio, bucket, tenant_id, apply=False, min_age_hours=24, now=None):
    if tenant_id < 1 or min_age_hours < 1:
        raise ValueError("tenant_id and min_age_hours must be positive")
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(hours=min_age_hours)
    errors = 0
    prefix = f"t{tenant_id}/generated-video/"
    for item in minio.list_objects(bucket, prefix=prefix, recursive=True):
        key = item.object_name
        parsed = VIDEO_KEY.fullmatch(key)
        if not parsed or int(parsed[1]) != tenant_id:
            emit("skip", key, "unrecognized_key")
            continue
        if item.last_modified is None or item.last_modified > cutoff:
            emit("skip", key, "recent_object")
            continue
        try:
            with connection.transaction(), connection.cursor() as cursor:
                cursor.execute("SET LOCAL lock_timeout = '5s'")
                cursor.execute("LOCK TABLE assets IN SHARE MODE")
                reason = eligible(cursor, tenant_id, int(parsed[2]), key)
                if reason:
                    emit("skip", key, reason)
                    continue
                # Re-stat after acquiring locks; the listing may be stale.
                current = minio.stat_object(bucket, key)
                if current.last_modified is None or current.last_modified > cutoff:
                    emit("skip", key, "object_changed_or_recent")
                    continue
                if apply:
                    minio.remove_object(bucket, key)
                emit("deleted" if apply else "would_delete", key)
        except Exception as error:
            # Keep database rows untouched; rerunning rechecks every condition.
            errors += 1
            emit("error", key, type(error).__name__)
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tenant-id", required=True, type=int)
    parser.add_argument("--min-age-hours", default=24, type=int)
    parser.add_argument("--apply", action="store_true", help="delete eligible objects; otherwise only preview")
    args = parser.parse_args()
    if args.tenant_id < 1 or args.min_age_hours < 1:
        parser.error("tenant-id and min-age-hours must be positive")
    import psycopg
    from minio import Minio
    endpoint = urlsplit(os.environ["MINIO_ENDPOINT"])
    if endpoint.scheme not in ("http", "https") or not endpoint.netloc or endpoint.path not in ("", "/"):
        raise ValueError("invalid MINIO_ENDPOINT")
    client = Minio(endpoint.netloc, access_key=os.environ["MINIO_ACCESS_KEY"],
                   secret_key=os.environ["MINIO_SECRET_KEY"], secure=endpoint.scheme == "https")
    dsn = os.environ["DB_URL"].removeprefix("jdbc:")
    with psycopg.connect(dsn, user=os.environ.get("DB_MIGRATE_USER", os.environ["DB_USER"]),
                         password=os.environ.get("DB_MIGRATE_PASSWORD", os.environ["DB_PASSWORD"]),
                         autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")
            if not cursor.fetchone()[0]:
                raise PermissionError("maintenance DB role needs BYPASSRLS for global Asset-reference checks")
        return 1 if cleanup(connection, client, os.environ["MINIO_BUCKET"], args.tenant_id,
                            args.apply, args.min_age_hours) else 0


if __name__ == "__main__":
    raise SystemExit(main())
