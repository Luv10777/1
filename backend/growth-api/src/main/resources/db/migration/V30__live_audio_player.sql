ALTER TABLE live_sessions
    ADD COLUMN audio_route VARCHAR(30) NOT NULL DEFAULT 'loopback_adapter',
    ADD COLUMN audio_test_status VARCHAR(20) NOT NULL DEFAULT 'untested',
    ADD COLUMN player_paired BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN player_last_heartbeat_at TIMESTAMPTZ;

ALTER TABLE live_sessions
    ADD CONSTRAINT ck_live_audio_route
        CHECK (audio_route IN ('loopback_adapter', 'pc_soundcard', 'speaker_pickup')),
    ADD CONSTRAINT ck_live_audio_test_status
        CHECK (audio_test_status IN ('untested', 'confirmed', 'failed'));
