//go:build !windows

package localtier

// restrictFile: the 0600 the file was written with is the whole ACL here.
func restrictFile(string) error { return nil }
