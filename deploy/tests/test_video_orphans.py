import importlib.util
import io
import os
import sys
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("video_orphans", Path(__file__).parents[1] / "cleanup-video-orphans.py")
script = importlib.util.module_from_spec(spec)
spec.loader.exec_module(script)


class VideoOrphanCleanupTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 6, tzinfo=timezone.utc)
        self.key = "t7/generated-video/42/output.mp4"
        self.connection = MagicMock()
        self.cursor = self.connection.cursor.return_value.__enter__.return_value
        self.cursor.fetchone.side_effect = [("FAILED", None, 0), (False,), (False,), (False,)]
        self.minio = MagicMock()
        self.minio.list_objects.return_value = [self.object(self.key)]
        self.minio.stat_object.return_value = self.object(self.key)

    def object(self, key, modified=None):
        return SimpleNamespace(object_name=key, last_modified=modified or self.now - timedelta(days=2))

    def run_cleanup(self, **kwargs):
        with redirect_stdout(io.StringIO()) as output:
            errors = script.cleanup(self.connection, self.minio, "test-bucket", 7, now=self.now, **kwargs)
        return errors, output.getvalue()

    def test_default_dry_run_previews_and_never_deletes(self):
        errors, output = self.run_cleanup()
        self.assertEqual(errors, 0)
        self.assertIn("would_delete", output)
        self.minio.remove_object.assert_not_called()
        self.minio.list_objects.assert_called_once_with("test-bucket", prefix="t7/generated-video/", recursive=True)

    def test_apply_deletes_only_after_global_reference_checks_with_locks_held(self):
        def remove(bucket, key):
            statements = [call.args[0] for call in self.cursor.execute.call_args_list]
            self.assertIn("LOCK TABLE assets IN SHARE MODE", statements)
            self.assertTrue(any("FOR UPDATE" in sql for sql in statements))
            self.assertTrue(any("FROM assets WHERE storage_key=%s" in sql for sql in statements))
            self.connection.transaction.return_value.__exit__.assert_not_called()
        self.minio.remove_object.side_effect = remove
        errors, output = self.run_cleanup(apply=True)
        self.assertEqual(errors, 0)
        self.assertIn("deleted", output)
        self.minio.remove_object.assert_called_once_with("test-bucket", self.key)
        self.assertFalse(any(call.args[0].startswith(("UPDATE", "DELETE", "INSERT"))
                             for call in self.cursor.execute.call_args_list))

    def test_cancelled_unpublished_unreferenced_workflow_is_eligible(self):
        self.cursor.fetchone.side_effect = [("CANCELLED", None, 0), (False,), (False,), (False,)]
        errors, output = self.run_cleanup(apply=True)
        self.assertEqual(errors, 0)
        self.assertIn("deleted", output)
        self.minio.remove_object.assert_called_once_with("test-bucket", self.key)

    def test_every_active_successful_or_published_workflow_is_protected(self):
        cases = [(state, None) for state in ("QUEUED", "SUBMITTING", "GENERATING", "IMPORTING", "QA", "SUCCEEDED")]
        for state, publication in cases + [("FAILED", self.now), ("CANCELLED", self.now)]:
            with self.subTest(state=state, publication=publication):
                self.cursor.fetchone.side_effect = [(state, publication, 0)]
                self.minio.remove_object.reset_mock()
                errors, output = self.run_cleanup(apply=True)
                self.assertEqual(errors, 0)
                self.assertIn("workflow_active_or_published", output)
                self.minio.remove_object.assert_not_called()

    def test_a_missing_workflow_is_not_evidence_that_deletion_is_safe(self):
        self.cursor.fetchone.side_effect = [None]
        self.run_cleanup(apply=True)
        self.minio.remove_object.assert_not_called()

    def test_legacy_completed_progress_is_protected_without_a_publication_marker(self):
        self.cursor.fetchone.side_effect = [("FAILED", None, 100)]
        _, output = self.run_cleanup(apply=True)
        self.assertIn("workflow_active_or_published", output)
        self.minio.remove_object.assert_not_called()

    def test_any_asset_reference_including_invalid_and_cross_tenant_protects_the_object(self):
        self.cursor.fetchone.side_effect = [("FAILED", None, 0), (True,)]
        _, output = self.run_cleanup(apply=True)
        self.assertIn("asset_referenced", output)
        self.minio.remove_object.assert_not_called()

    def test_a_published_workflow_reference_without_an_asset_still_protects_the_object(self):
        self.cursor.fetchone.side_effect = [("FAILED", None, 0), (False,), (True,)]
        _, output = self.run_cleanup(apply=True)
        self.assertIn("published_reference", output)
        self.minio.remove_object.assert_not_called()

    def test_pending_or_running_tasks_prevent_deletion(self):
        self.cursor.fetchone.side_effect = [("CANCELLED", None, 0), (False,), (False,), (True,)]
        _, output = self.run_cleanup(apply=True)
        self.assertIn("task_active", output)
        self.minio.remove_object.assert_not_called()

    def test_unknown_keys_and_other_tenant_prefixes_never_reach_deletion(self):
        self.minio.list_objects.return_value = [self.object(key) for key in
            ("t8/generated-video/42/output.mp4", "t7/image/output.mp4", "t7/generated-video/42/other.mp4",
             "t7/generated-video/42/../output.mp4", "t7/generated-video/0/output.mp4")]
        self.run_cleanup(apply=True)
        self.minio.remove_object.assert_not_called()
        self.cursor.execute.assert_not_called()

    def test_retry_and_import_candidate_paths_are_recognized(self):
        for key in ("t7/generated-video/42/retry-2/output.mp4",
                    "t7/generated-video/42/import-00000000-0000-0000-0000-000000000001/output.mp4",
                    "t7/generated-video/42/retry-2/import-00000000-0000-0000-0000-000000000001/output.mp4"):
            with self.subTest(key=key):
                self.cursor.fetchone.side_effect = [("FAILED", None, 0), (False,), (False,), (False,)]
                self.minio.list_objects.return_value = [self.object(key)]
                self.minio.remove_object.reset_mock()
                self.run_cleanup(apply=True)
                self.minio.remove_object.assert_called_once_with("test-bucket", key)

    def test_recent_or_replaced_objects_are_protected(self):
        self.minio.list_objects.return_value = [self.object(self.key, self.now)]
        self.run_cleanup(apply=True)
        self.minio.remove_object.assert_not_called()
        self.minio.list_objects.return_value = [self.object(self.key)]
        self.minio.stat_object.return_value = self.object(self.key, self.now)
        _, output = self.run_cleanup(apply=True)
        self.assertIn("object_changed_or_recent", output)
        self.minio.remove_object.assert_not_called()

    def test_reference_check_or_lock_failure_never_attempts_deletion(self):
        self.cursor.execute.side_effect = RuntimeError("database unavailable")
        errors, output = self.run_cleanup(apply=True)
        self.assertEqual(errors, 1)
        self.assertIn("error", output)
        self.minio.remove_object.assert_not_called()

    def test_storage_delete_failure_is_reported_without_database_mutation(self):
        self.minio.remove_object.side_effect = RuntimeError("MinIO unavailable")
        errors, output = self.run_cleanup(apply=True)
        self.assertEqual(errors, 1)
        self.assertIn("RuntimeError", output)
        self.assertFalse(any(call.args[0].startswith(("UPDATE", "DELETE", "INSERT"))
                             for call in self.cursor.execute.call_args_list))

    def test_object_stat_failure_and_unknown_age_never_attempt_deletion(self):
        self.minio.stat_object.side_effect = RuntimeError("object missing or unavailable")
        errors, _ = self.run_cleanup(apply=True)
        self.assertEqual(errors, 1)
        self.minio.remove_object.assert_not_called()
        self.minio.list_objects.return_value = [SimpleNamespace(object_name=self.key, last_modified=None)]
        self.run_cleanup(apply=True)
        self.minio.remove_object.assert_not_called()

    def test_a_role_subject_to_rls_is_rejected_before_any_object_listing(self):
        self.cursor.fetchone.side_effect = [(False,)]
        self.connection.__enter__.return_value = self.connection
        env = {"DB_URL": "jdbc:postgresql://localhost/test", "DB_USER": "app", "DB_PASSWORD": "unused",
               "MINIO_ENDPOINT": "http://localhost:9000", "MINIO_ACCESS_KEY": "unused", "MINIO_SECRET_KEY": "unused",
               "MINIO_BUCKET": "test-bucket"}
        with patch.dict(os.environ, env, clear=True), patch.object(sys, "argv", ["cleanup", "--tenant-id", "7"]), \
             patch.dict(sys.modules, {"psycopg": SimpleNamespace(connect=lambda *a, **k: self.connection),
                                      "minio": SimpleNamespace(Minio=lambda *a, **k: self.minio)}):
            with self.assertRaises(PermissionError):
                script.main()
        self.minio.list_objects.assert_not_called()
        self.minio.remove_object.assert_not_called()


if __name__ == "__main__":
    unittest.main()
