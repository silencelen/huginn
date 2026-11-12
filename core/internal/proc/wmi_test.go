package proc

import (
	"strings"
	"testing"
)

// The core's direct start on Windows must create its console hidden: a visible
// one showed its log and ended the core when it was closed (2026-11-11).
func TestWMICreateScriptHidesTheWindow(t *testing.T) {
	s := WMICreateScript(`"C:\\Users\\o'brien\\AppData\\Local\\huginn\\huginn.exe" core run`)
	for _, want := range []string{
		"New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }",
		"ProcessStartupInformation = $si",
		"-MethodName Create",
		"exit [int]$r.ReturnValue",
	} {
		if !strings.Contains(s, want) {
			t.Errorf("script lacks %q:\n%s", want, s)
		}
	}
	if !strings.Contains(s, `o''brien`) || strings.Contains(s, `o'brien`) {
		t.Errorf("a quote in the path is not doubled for PowerShell:\n%s", s)
	}
}
