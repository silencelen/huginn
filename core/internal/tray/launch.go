package tray

import "strings"

// toastLaunch is what a Windows toast opens when it, or its Open button, is
// clicked: the notice's URL when it is a huginn:// link, and the bare
// huginn://open (bring the app up) otherwise. A protocol toast hands that
// string to the shell verbatim, so anything else would make a trusted "huginn"
// toast open whatever another scheme's handler is. It is the rule OpenURL
// already applies to the Linux click path, applied where Windows has no
// OpenURL in between.
func toastLaunch(u string) string {
	if strings.HasPrefix(u, "huginn://") {
		return u
	}
	return "huginn://open"
}
