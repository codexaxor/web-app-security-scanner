import hashlib, subprocess, os


def weak_hash(data):
    return hashlib.md5(data).hexdigest()


def run_cmd(user):
    subprocess.Popen(user, shell=True)
    os.system("ls " + user)


def do_eval(user):
    return eval(user)
