package main

import "strings"

// sshArg is the value on an ssh_config line whose fields are f (f[0] is the
// keyword, and there are at least two): f[1], unless the value is in double
// quotes, which ssh_config(5) allows for "arguments containing spaces", and
// then everything between them.
//
// ⚠ THE KEY PATH IS WRITTEN QUOTED, SO IT HAS TO BE READ THAT WAY. A Windows
// profile is often C:\Users\First Last, and `IdentityFile C:\Users\First
// Last\.ssh\id_ed25519` unquoted is "extra arguments at end of line" to
// OpenSSH: a fatal error for EVERY host in the file, git over ssh included,
// not only huginn. So `huginn setup ssh` and both installers write
// IdentityFile "<path>", and a reader that took f[1] would get `"C:\Users\First`.
func sshArg(line string, f []string) string {
	k := strings.Index(line, f[0]) + len(f[0])
	rest := strings.TrimLeft(line[k:], " \t")
	if strings.HasPrefix(rest, `"`) {
		if j := strings.IndexByte(rest[1:], '"'); j >= 0 {
			return rest[1 : 1+j]
		}
	}
	return f[1]
}
