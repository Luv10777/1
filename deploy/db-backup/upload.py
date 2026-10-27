"""Upload one encrypted backup to the private COS bucket and confirm each copy arrived intact.

Usage: python3 -I upload.py <growth-api.env> <file> <key> [<key> ...]
Uses the COS_SECRET_ID / COS_SECRET_KEY / COS_BUCKET / COS_REGION already configured for the backend.
"""
import base64
import hashlib
import pathlib
import sys

import boto3
from botocore.config import Config


def settings(path):
    values = {}
    for line in pathlib.Path(path).read_text().splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip()
    return values


def client(values):
    region = values['COS_REGION']
    options = {'signature_version': 's3v4', 's3': {'addressing_style': 'virtual'}}
    try:
        # Newer botocore adds CRC checksums by default, which COS does not accept; older versions lack these options.
        config = Config(**options, request_checksum_calculation='when_required', response_checksum_validation='when_required')
    except TypeError:
        config = Config(**options)
    return boto3.client('s3', endpoint_url=f'https://cos.{region}.myqcloud.com', region_name=region,
                        aws_access_key_id=values['COS_SECRET_ID'], aws_secret_access_key=values['COS_SECRET_KEY'],
                        config=config)


def main(env_file, source, *keys):
    values = settings(env_file)
    cos = client(values)
    data = pathlib.Path(source).read_bytes()
    md5 = base64.b64encode(hashlib.md5(data).digest()).decode()
    for key in keys:
        cos.put_object(Bucket=values['COS_BUCKET'], Key=key, Body=data, ContentMD5=md5,
                       ContentType='application/octet-stream')
        stored = cos.head_object(Bucket=values['COS_BUCKET'], Key=key)['ContentLength']
        if stored != len(data):
            sys.exit(f'{key}: stored {stored} bytes, expected {len(data)}')
        print(f'uploaded {key} ({len(data)} bytes)')


if __name__ == '__main__':
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
