package main

import (
	"crypto/md5"
	"fmt"
	"os/exec"
)

func weak() { h := md5.New(); fmt.Println(h) }

func run(user string) { exec.Command("sh", "-c", user).Run() }
