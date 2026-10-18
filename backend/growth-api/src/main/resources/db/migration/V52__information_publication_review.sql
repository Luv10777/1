-- New and edited information must be reviewed before becoming public.
ALTER TABLE information_publications
    DROP CONSTRAINT information_publications_status_check;
ALTER TABLE information_publications
    ADD CONSTRAINT ck_information_publications_status
        CHECK (status IN ('PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'OFFLINE')),
    ALTER COLUMN status SET DEFAULT 'PENDING_REVIEW',
    ALTER COLUMN published_at DROP NOT NULL,
    ALTER COLUMN published_at DROP DEFAULT,
    ADD COLUMN review_note VARCHAR(500),
    ADD CONSTRAINT ck_information_publications_published_at
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL);
