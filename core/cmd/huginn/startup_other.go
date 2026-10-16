//go:build !windows

package main

func startupDisabled(string) bool { return false }
