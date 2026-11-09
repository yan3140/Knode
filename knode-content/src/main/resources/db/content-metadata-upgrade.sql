-- Manual structural upgrade from the R2 schema; do not run on new schemas.
ALTER TABLE content_category ADD COLUMN description VARCHAR(1024);
ALTER TABLE content_tag ADD COLUMN remark VARCHAR(1024);
