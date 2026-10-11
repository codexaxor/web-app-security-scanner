-- Single-tenant mode needs exactly one workspace. This is real data every table references,
-- not dev seed data (D-011). The fixed id matches Workspace.DEFAULT_ID in code.

insert into workspace (id, name)
values ('00000000-0000-0000-0000-000000000001', 'Default');
