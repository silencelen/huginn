package console

import (
	"reflect"
	"strings"
	"unicode/utf8"
)

// Server and model text is shown, never obeyed (security scan F25,
// 2026-10-18). Chat messages, streamed answers, tool inputs, titles,
// snippets, round headlines and pane previews are written by a model, and a
// model writes what it read: a log line, a fetched page, a file name. A
// terminal EXECUTES some of what it is handed. OSC 52 writes the clipboard,
// OSC 0 retitles the window, OSC 8 makes a link, and CSI clears and redraws
// the screen or reprints a line over another. lipgloss keeps every such
// sequence intact while it wraps and pads.

// clean is s without what a terminal acts on instead of printing: the C0
// controls other than newline and tab (ESC, CR, BEL and backspace among
// them), DEL, and the C1 controls U+0080-U+009F, which some terminals read as
// one-character ESC sequences (U+009B is CSI). This is the set appd strips
// from round text, plus the tab. Bytes that are not UTF-8 become U+FFFD, so a
// lone 0x9B cannot arrive as an 8-bit CSI either. Pure.
func clean(s string) string {
	if utf8.ValidString(s) && strings.IndexFunc(s, acts) < 0 {
		return s
	}
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s { // an invalid byte arrives here as U+FFFD
		if !acts(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// acts: r is a control a terminal acts on rather than shows.
func acts(r rune) bool {
	return r < 0x20 && r != '\n' && r != '\t' || r >= 0x7f && r <= 0x9f
}

// scrub cleans, in place, every string reachable from v, which is what a
// response was decoded into. That covers struct fields (embedded ones too),
// slice and array elements, map keys and values, and whatever pointers and
// interfaces hold. It runs once, where responses are decoded (backend.do and
// the chat stream), so every field reaches every screen clean, including
// fields added later. IDs and names are cleaned like the rest. appd never
// puts a control in one, and tmux escapes them in a session name.
func scrub(v any) { scrubValue(reflect.ValueOf(v)) }

func scrubValue(v reflect.Value) {
	switch v.Kind() {
	case reflect.String:
		if v.CanSet() {
			if s, c := v.String(), clean(v.String()); c != s {
				v.SetString(c)
			}
		}
	case reflect.Pointer:
		if !v.IsNil() {
			scrubValue(v.Elem())
		}
	case reflect.Interface:
		if v.IsNil() {
			return
		}
		e := v.Elem()
		if e.Kind() == reflect.Pointer {
			scrubValue(e)
			return
		}
		if v.CanSet() { // a value in an interface is a copy: clean one, put it back
			c := reflect.New(e.Type()).Elem()
			c.Set(e)
			scrubValue(c)
			v.Set(c)
		}
	case reflect.Struct:
		// Every field, settable or not: an embedded struct of an unexported
		// type is read-only itself, yet its exported fields are set (by
		// encoding/json, and here). A string reached through an unexported
		// field stays read-only, so it is left alone.
		for i := 0; i < v.NumField(); i++ {
			scrubValue(v.Field(i))
		}
	case reflect.Slice, reflect.Array:
		if v.Type().Elem().Kind() == reflect.Uint8 {
			return // bytes (json.RawMessage), never shown as text
		}
		for i := 0; i < v.Len(); i++ {
			scrubValue(v.Index(i))
		}
	case reflect.Map:
		if v.IsNil() || !v.CanInterface() { // nil, or reached through an unexported field
			return
		}
		// Map entries are not addressable: clean copies, then put them back
		// under their cleaned keys.
		type entry struct{ was, k, e reflect.Value }
		var all []entry
		for it := v.MapRange(); it.Next(); {
			k := reflect.New(v.Type().Key()).Elem()
			k.Set(it.Key())
			e := reflect.New(v.Type().Elem()).Elem()
			e.Set(it.Value())
			scrubValue(k)
			scrubValue(e)
			all = append(all, entry{it.Key(), k, e})
		}
		for _, x := range all {
			v.SetMapIndex(x.was, reflect.Value{})
		}
		for _, x := range all {
			v.SetMapIndex(x.k, x.e)
		}
	}
}
