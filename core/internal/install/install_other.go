//go:build !windows

package install

import "errors"

func installWindows(Layout, string) (Result, error) {
	return Result{}, errors.New("windows layout on a non-windows build")
}
