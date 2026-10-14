-- M3 manual-check fix: a line-oriented preview step (cut -c1-4 in scanner-image/secscan-lib.sh) emitted the
-- first 4 characters of EVERY line of a multi-line secret (e.g. a PEM private key), leaking a fragment of each
-- line into secret_preview. The scanner now previews the whole secret as one string (<= 4 chars, no newline),
-- and the worker clamps anyway. This migration scrubs any rows already written by the old code: a valid preview
-- is at most 4 characters and single-line, so anything longer or containing a CR/LF is replaced with the
-- fully-masked '****'. (V8 is already applied and must not be edited — CLAUDE.md.)

update sub_issue
   set secret_preview = '****'
 where secret_preview is not null
   and (char_length(secret_preview) > 4
        or secret_preview like E'%\n%'
        or secret_preview like E'%\r%');

update scan_finding
   set secret_preview = '****'
 where secret_preview is not null
   and (char_length(secret_preview) > 4
        or secret_preview like E'%\n%'
        or secret_preview like E'%\r%');
