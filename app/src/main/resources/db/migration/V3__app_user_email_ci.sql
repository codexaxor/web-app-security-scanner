-- Emails are case-insensitive identifiers. Store them lowercased and enforce uniqueness on lower(email).

update app_user set email = lower(email) where email <> lower(email);

alter table app_user drop constraint uq_app_user_workspace_email;

create unique index uq_app_user_email_lower on app_user (lower(email));
