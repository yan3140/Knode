-- Manual 07A additive index for author profile article pagination. Never auto-applied.
CREATE INDEX ix_content_article_author_public ON content_article(author_id,status,deleted,created_at,id);
