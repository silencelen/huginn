package tray

import "testing"

// A toast only ever launches a huginn:// link, whatever the notice carried.
func TestToastLaunchIsAlwaysHuginn(t *testing.T) {
	for _, u := range []string{
		"",
		"https://example.invalid/login",
		"file:///C:/Windows/System32/calc.exe",
		"ms-settings:privacy",
		"search-ms:query=x",
		"javascript:alert(1)",
		" huginn://open?view=sessions&id=main",
		"HUGINN://open?view=sessions&id=main",
	} {
		if got := toastLaunch(u); got != "huginn://open" {
			t.Errorf("toastLaunch(%q) = %q, want huginn://open", u, got)
		}
	}
	for _, u := range []string{"huginn://open", "huginn://open?view=sessions&id=main", "huginn://open?view=chats&id=c1"} {
		if got := toastLaunch(u); got != u {
			t.Errorf("toastLaunch(%q) = %q, want it unchanged", u, got)
		}
	}
}
