# Synthetic fixture: the value is an obviously fake canary, never a real secret.
PASSWORD = "L3AKcanaryZZZ9000_fake_do_not_use"

def connect
  PASSWORD
end
