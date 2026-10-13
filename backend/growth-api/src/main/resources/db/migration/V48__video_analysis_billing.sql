-- Existing analyses keep their original billing state; only new submissions reserve credits.
ALTER TABLE video_analyses ADD COLUMN billing_reservation_id VARCHAR(36)
    UNIQUE REFERENCES billing_reservations(id);
