-- Expand-only column for #227: NULL is transitional; authorization enforcement
-- must be fail-closed for high-privilege token policies before stable promotion.
ALTER TABLE ci_token_policy
    ADD COLUMN oidc_constraints LONGTEXT NULL;
