//go:build !linux

package lock

import (
	"context"
	"errors"
)

func login1Sessions(context.Context) ([]Session, error) { return nil, errors.New("no logind here") }
