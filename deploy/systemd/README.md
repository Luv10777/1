# systemd deployment

Copy these unit files to `/etc/systemd/system/` on the production host, then run:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now wuyao-api.service wuyao-worker.service
sudo systemctl enable --now wuyao-image-worker@1.service wuyao-image-worker@2.service wuyao-image-worker@3.service
```

The units reference the release symlink at `/home/ubuntu/wuyao-current`.
Deploy a built Jar to a new release directory and switch that symlink before restarting services.
Never rebuild or replace the Jar used by running services. After switching the symlink,
restart the API, default worker, and all image workers so every process loads the same release:

```bash
sudo systemctl restart wuyao-api.service wuyao-worker.service \
  wuyao-image-worker@1.service wuyao-image-worker@2.service wuyao-image-worker@3.service
```

`/etc/wuyao/growth-api.env` is production-only configuration. Keep credentials there;
do not replace it from the repository during deployment. The checked-in `.env.example`
records non-secret provider settings and leaves API keys empty.
