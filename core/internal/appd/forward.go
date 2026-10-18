package appd

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
)

// MaxForwardBody is the largest request body Forward carries: appd's own
// limit for an upload (huginn-appd.js MAX_UPLOAD_BYTES).
const MaxForwardBody = 128 << 20

// forwardedRequestHeaders are the caller's headers that reach appd. A caller's
// own X-Huginn-* (the desktop's client id and notify claim) wins over the
// core's, so appd keeps telling the desktop and the core apart; Authorization
// is never taken from a caller.
var forwardedRequestHeaders = []string{"Content-Type", "Accept", "Range", "If-None-Match", "If-Modified-Since"}

// hopByHop are the headers that describe one connection, never forwarded.
var hopByHop = map[string]bool{
	"Connection": true, "Keep-Alive": true, "Proxy-Authenticate": true, "Proxy-Authorization": true,
	"Te": true, "Trailer": true, "Transfer-Encoding": true, "Upgrade": true,
}

// ForwardableResponseHeader: a response header the proxy passes back.
func ForwardableResponseHeader(name string) bool { return !hopByHop[http.CanonicalHeaderKey(name)] }

// Forward sends one request on a caller's behalf - the core's /appd proxy -
// with this client's token and TLS rules, and returns appd's response with
// its body OPEN, whatever its status: the caller copies it through as it
// arrives (an event stream, a long poll, a download) and closes it.
//
// body is streamed, never buffered. A request refused at the TLS handshake
// for a rotated certificate is re-proved and sent ONCE more, which is safe
// only because the handshake comes before any body byte is read; if one had
// been, the refusal is returned as it is.
//
// No request timeout: ctx (the caller's connection) ends it. Dialling and the
// TLS handshake keep their own limits.
func (c *Client) Forward(ctx context.Context, method, pathQuery string, in http.Header, body io.Reader) (*http.Response, error) {
	var cr *countingReader
	if body != nil {
		cr = &countingReader{r: body}
	}
	var res *http.Response
	err := c.withRoutes(ctx, func() error {
		// Another address is tried only while the body is untouched.
		if cr != nil && cr.n.Load() > 0 {
			return errBodySent
		}
		var err error
		res, err = c.forward1(ctx, method, pathQuery, in, cr)
		return err
	})
	if errors.Is(err, errBodySent) {
		return nil, errors.New("the request body was already sent to an address that then failed; send it again")
	}
	return res, err
}

var errBodySent = errors.New("body already sent")

func (c *Client) forward1(ctx context.Context, method, pathQuery string, in http.Header, cr *countingReader) (*http.Response, error) {
	target, err := c.resolve(pathQuery)
	if err != nil {
		return nil, err
	}
	tok, err := ResolveToken(c.tokens)
	if err != nil {
		return nil, err
	}
	switch target.Scheme {
	case "https":
		pins := c.loadPins(tok)
		if len(pins) == 0 {
			if pins, err = c.proveAndPin(ctx, tok, nil); err != nil {
				return nil, err
			}
		}
		res, err := c.forwardOnce(ctx, c.pinnedTransport(tok, pins), tok, method, target, in, cr)
		var pm *PinMismatchError
		if !errors.As(err, &pm) || (cr != nil && cr.n.Load() > 0) {
			return res, err
		}
		if pins, err = c.proveAndPin(ctx, tok, pins); err != nil {
			return nil, err
		}
		res, err = c.forwardOnce(ctx, c.pinnedTransport(tok, pins), tok, method, target, in, cr)
		if errors.As(err, &pm) {
			return nil, interceptedAt(c.origin(), fmt.Sprintf(" (certificate %s changed again after it was proved)", pm.FP))
		}
		return res, err
	case "http":
		if err := plainRefusal(target); err != nil {
			return nil, err
		}
		return c.forwardOnce(ctx, c.plainTransport(), tok, method, target, in, cr)
	}
	return nil, fmt.Errorf("%q is not an http(s) url", target.String())
}

func (c *Client) forwardOnce(ctx context.Context, t *http.Transport, tok, method string, target *url.URL, in http.Header, body *countingReader) (*http.Response, error) {
	var rd io.Reader
	if body != nil {
		// The transport closes a request body on its way out; the caller's
		// stays open, so a re-proved retry can still read it.
		rd = io.NopCloser(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, target.String(), rd)
	if err != nil {
		return nil, err
	}
	c.stamp(req)
	for _, h := range forwardedRequestHeaders {
		if v := in.Values(h); len(v) > 0 {
			req.Header[h] = append([]string(nil), v...)
		}
	}
	for k, v := range in {
		if strings.HasPrefix(http.CanonicalHeaderKey(k), "X-Huginn-") && !strings.EqualFold(k, "X-Huginn-Core-Proto") {
			req.Header[http.CanonicalHeaderKey(k)] = append([]string(nil), v...)
		}
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	hc := &http.Client{Transport: t, CheckRedirect: noRedirect}
	res, err := hc.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			return nil, fmt.Errorf("%s %s: %w", method, target.Path, ue.Err)
		}
		return nil, err
	}
	return res, nil
}

type countingReader struct {
	r io.Reader
	n atomic.Int64
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n.Add(int64(n))
	return n, err
}
