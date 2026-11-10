# Synthetic fixture (M8.15 review B3): the last line is a deliberate PARSE ERROR carrying an obviously fake
# canary. Opengrep's --verbose log quotes the unparsable source ("`...` was unexpected"), so this proves the
# verbose run log never reaches /out, the scanner's stdout/stderr (= the stored log excerpt) or the DB.
import os


def run(cmd):
    os.system("ls " + cmd)


def broken(:  L3AKfailCanary8888_fake_do_not_use = 1 )))
