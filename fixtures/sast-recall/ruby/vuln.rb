def run(x)
  system("ls #{x}")
  eval(x)
  `cat #{x}`
end
