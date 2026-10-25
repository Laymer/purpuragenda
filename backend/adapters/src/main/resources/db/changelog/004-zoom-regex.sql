-- PostgreSQL ARE bounds are limited to255. Preserve the intended1–256 pwd limit.
ALTER TABLE booking DROP CONSTRAINT booking_meeting_url_check;
ALTER TABLE booking ADD CONSTRAINT booking_meeting_url_check CHECK (
 meeting_url IS NULL OR meeting_url ~ '^https://[^/@:]+/j/[0-9]{9,12}(\?pwd=[A-Za-z0-9_.~-]{1,255}[A-Za-z0-9_.~-]?)?$'
);
