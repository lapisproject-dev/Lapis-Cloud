-- V1.9.234: soft delete of MANUAL mailing lists. Purely additive; V125 and every earlier migration are untouched.
-- deleted_at: UTC stamp (class A) of the deletion; NULL = live list. deleted_by: the BOARD/ADMIN member who deleted it.
ALTER TABLE mailing_list ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL;
ALTER TABLE mailing_list ADD COLUMN IF NOT EXISTS deleted_by UUID NULL REFERENCES member (id);

-- Only a MANUAL list can ever be deleted (server rule); the DB pins it too.
ALTER TABLE mailing_list DROP CONSTRAINT IF EXISTS chk_mailing_list_deleted_manual;
ALTER TABLE mailing_list ADD CONSTRAINT chk_mailing_list_deleted_manual
    CHECK (deleted_at IS NULL OR kind = 'MANUAL');
ALTER TABLE mailing_list DROP CONSTRAINT IF EXISTS chk_mailing_list_deleted_pair;
ALTER TABLE mailing_list ADD CONSTRAINT chk_mailing_list_deleted_pair
    CHECK ((deleted_at IS NULL) = (deleted_by IS NULL));
