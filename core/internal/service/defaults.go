package service

import (
	"errors"
	"os"
	"os/user"
	"path/filepath"
	"strings"
)

// LinuxDefaults fills LinuxOptions from this process's environment.
func LinuxDefaults(binary string) (LinuxOptions, error) {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return LinuxOptions{}, errors.New("service: no home directory")
	}
	cfg := os.Getenv("XDG_CONFIG_HOME")
	if !filepath.IsAbs(cfg) {
		cfg = filepath.Join(home, ".config")
	}
	name := os.Getenv("USER")
	if u, err := user.Current(); err == nil && u.Username != "" {
		name = u.Username
	}
	if name == "" {
		return LinuxOptions{}, errors.New("service: cannot tell which user this is")
	}
	return LinuxOptions{
		Binary: binary, Home: home, User: name, ConfigHome: cfg,
		Graphical: GraphicalSession(os.Getenv), Runner: ExecRunner{},
	}, nil
}

// WindowsDefaults fills WindowsOptions from this process's environment.
func WindowsDefaults(binary string) (WindowsOptions, error) {
	appData := os.Getenv("APPDATA")
	local := os.Getenv("LOCALAPPDATA")
	if appData == "" || local == "" {
		return WindowsOptions{}, errors.New("service: APPDATA/LOCALAPPDATA are not set")
	}
	name := os.Getenv("USERNAME")
	if dom := os.Getenv("USERDOMAIN"); dom != "" && name != "" {
		name = dom + `\` + name
	}
	if u, err := user.Current(); err == nil && strings.Contains(u.Username, `\`) {
		name = u.Username // DOMAIN\name from the token, not the environment
	}
	if name == "" {
		return WindowsOptions{}, errors.New("service: cannot tell which user this is")
	}
	return WindowsOptions{
		Binary: binary, User: name, AppData: appData,
		StateDir: filepath.Join(local, "huginn", "state"),
		Logon:    LogonS4U, Runner: ExecRunner{},
	}, nil
}
