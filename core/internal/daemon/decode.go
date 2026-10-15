package daemon

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

// decode reads one JSON object, refusing unknown fields (a typo in a PATCH
// must not silently do nothing) and anything over 64 KiB.
func decode(r *http.Request, v any) error {
	dec := json.NewDecoder(io.LimitReader(r.Body, 64<<10))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		if errors.Is(err, io.EOF) {
			return errors.New("empty body")
		}
		return err
	}
	return nil
}
