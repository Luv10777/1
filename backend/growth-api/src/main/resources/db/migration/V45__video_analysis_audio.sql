ALTER TABLE video_analyses
    ADD COLUMN has_audio BOOLEAN,
    ADD COLUMN audio_storage_key TEXT,
    ADD COLUMN audio_analysis JSONB;
