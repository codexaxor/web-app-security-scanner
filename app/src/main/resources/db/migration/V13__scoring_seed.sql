-- M5 seed data (additive). Fix-time minutes per category (SPEC "Fix time"; tunable — change by a new migration,
-- never by editing this one once applied). SCA: patch/minor upgrade 15, major upgrade 60; secret rotation 10.
-- SAST/IaC/EOL categories are seeded now but not consumed until M6. DEFAULT covers an unmapped category.
insert into fix_time_config (category, minutes) values
    ('SCA_PATCH_MINOR', 15),
    ('SCA_MAJOR',       60),
    ('SECRET_ROTATION', 10),
    ('SAST_INJECTION',  30),
    ('IAC_MISCONFIG',   15),
    ('EOL_UPGRADE',    300),
    ('DEFAULT',         20);
