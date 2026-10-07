# systemd deployment

These units run the API and dedicated workers for all four production task queues.
Runtime: Spring Boot 3.5 / Java 21. Updated: 2026-10-05.

Copy these unit files to `/etc/systemd/system/` on the production host, then run:

```bash
sudo systemd-analyze verify /etc/systemd/system/wuyao-video-worker.service \
  /etc/systemd/system/wuyao-media-worker.service
sudo systemctl daemon-reload
sudo systemctl enable --now wuyao-api.service wuyao-worker.service \
  wuyao-image-worker@1.service wuyao-image-worker@2.service wuyao-image-worker@3.service \
  wuyao-video-worker.service wuyao-media-worker.service
```

The units reference the release symlink at `/home/ubuntu/wuyao-current`.
Deploy a built Jar to a new release directory and switch that symlink before restarting services.
Never rebuild or replace the Jar used by running services. After switching the symlink,
restart the API and all default, image, video, and media workers so every process loads the same
release:

```bash
sudo systemctl restart wuyao-api.service wuyao-worker.service \
  wuyao-image-worker@1.service wuyao-image-worker@2.service wuyao-image-worker@3.service \
  wuyao-video-worker.service wuyao-media-worker.service
```

The image instances listen on `127.0.0.1:8091` through `8093`; the default worker uses `8090`.
The video and media workers use `127.0.0.1:8094` and `127.0.0.1:8095`, respectively.
All units use `ubuntu:ubuntu`, the same working directory and configuration import, and the existing
restart, shutdown, permission, and journal logging conventions.

| Queue | Consumer unit | Parallelism | Maximum JVM heap |
|---|---|---|---|
| `DEFAULT` | `wuyao-worker.service` | Application default: 1 | 1536 MiB |
| `IMAGE` | `wuyao-image-worker@.service` | 4 per instance | 1024 MiB per instance |
| `VIDEO_PROVIDER` | `wuyao-video-worker.service` | 2 | 1024 MiB |
| `MEDIA_CPU` | `wuyao-media-worker.service` | 2 | 4096 MiB |

Keep the video and media queues in separate processes: downloads and media probing must not occupy
the provider worker's execution slots or heap. Provider submissions and polls use two slots because
polling consists of short requests separated by delayed tasks; waiting for `run_after` does not hold
an execution slot.

The media worker starts with a 512 MiB heap and allows up to 4 GiB, with two execution slots to
bound concurrent downloads and `ffprobe` processes. Imports stream videos of up to 1 GiB into object
storage; the larger heap leaves room for transfer buffers, storage-client allocations, and garbage
collection. Provision additional host RAM for JVM native memory and `ffprobe` children, whose
memory is outside `-Xmx`, as well as the other services.

Install `ffprobe` on the host and make it executable by `ubuntu`;
use `FFPROBE_PATH` in `/etc/wuyao/growth-api.env` if it is not on the service's PATH.

| Video task type | Queue | Consumer unit |
|---|---|---|
| `VIDEO_SUBMIT` | `VIDEO_PROVIDER` | `wuyao-video-worker.service` |
| `VIDEO_POLL` | `VIDEO_PROVIDER` | `wuyao-video-worker.service` |
| `VIDEO_IMPORT` | `MEDIA_CPU` | `wuyao-media-worker.service` |
| `VIDEO_QA` | `MEDIA_CPU` | `wuyao-media-worker.service` |

The deployed worker queue union is `DEFAULT,IMAGE,VIDEO_PROVIDER,MEDIA_CPU`, matching
`growth.worker.queues` in `application.yml`. Production Java task submissions also use only these
four queues: `ASSET_PROBE`, `IMAGE_PLAN`, and `IMAGE_DOWNLOAD` use `DEFAULT`; `IMAGE_RENDER` uses
`IMAGE`; the video task mapping is listed above. The `TEST` queue occurs only in integration tests.
The API keeps workers disabled by default, and each worker unit explicitly enables its own queue.

`/etc/wuyao/growth-api.env` is production-only configuration. Keep credentials there;
do not replace it from the repository during deployment. The checked-in `.env.example`
records non-secret provider settings and leaves API keys empty.
