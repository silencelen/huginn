package runner

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf8"
)

// ⚠⚠ THIS LOST ENTIRE ANSWERS (client/huginn-device, ported). A device runs
// claude with `--output-format stream-json --verbose --include-partial-messages`,
// so ONE line can carry a whole tool_result or a whole final answer. Over the
// daemon's 1 MiB body cap the POST came back 413 and the whole batch was lost;
// if the terminal frame was in it the ending could NEVER be delivered, so the
// chat sat `running` forever and the machine was blocked from every other job.
// So the runner keeps itself under the limit rather than discovering it: no
// batch is ever built too large, an oversized line is shrunk in place with a
// marker where the content was, and the terminal frame is posted ALONE.
const (
	maxLineBytes  = 128 * 1024
	maxBatchBytes = 192 * 1024
)

// shrinkBudgets are tried in order; each truncates every long string in the
// event to that many characters.
var shrinkBudgets = []int{32768, 8192, 1024, 200}

// trimStrings truncates every long string inside a decoded event, in place.
// Budgets count runes; Node counts UTF-16 units. The difference only moves
// where the cut falls, never whether the line fits (fit is checked in bytes).
func trimStrings(node any, budget int) any {
	switch v := node.(type) {
	case map[string]any:
		for k, x := range v {
			v[k] = trimStrings(x, budget)
		}
		return v
	case []any:
		for i, x := range v {
			v[i] = trimStrings(x, budget)
		}
		return v
	case string:
		if n := utf8.RuneCountInString(v); n > budget {
			r := []rune(v)
			return fmt.Sprintf("%s\n\n[… %d more characters were too large to send and were dropped]", string(r[:budget]), n-budget)
		}
		return v
	default:
		return v
	}
}

// encode marshals like JSON.stringify: no HTML escaping, numbers untouched.
func encode(v any) string {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return ""
	}
	return strings.TrimSuffix(b.String(), "\n")
}

// shrinkLine returns a line small enough to send, keeping its own event type
// wherever possible: a truncated tool_result still renders as a tool_result,
// where a replacement would lose the shape and everything in it.
func shrinkLine(line string) string {
	if len(line) <= maxLineBytes {
		return line
	}
	dec := json.NewDecoder(strings.NewReader(line))
	dec.UseNumber() // a 64-bit id must not come back rounded through float64
	var ev any
	if dec.Decode(&ev) == nil {
		for _, budget := range shrinkBudgets {
			ev = trimStrings(ev, budget)
			if out := encode(ev); out != "" && len(out) <= maxLineBytes {
				return out
			}
		}
	}
	// Unparseable, or structurally huge whatever we do. Said as assistant text
	// so it is VISIBLE in the transcript: a silently missing event is the thing
	// this whole file exists to stop.
	return encode(map[string]any{
		"type": "assistant",
		"message": map[string]any{"content": []any{map[string]any{
			"type": "text",
			"text": fmt.Sprintf("[one output event of %d bytes was too large to send and was dropped]", len(line)),
		}}},
	})
}

// batchLines splits lines into batches that each fit, preserving order.
func batchLines(lines []string) [][]string {
	var out [][]string
	var cur []string
	size := 0
	for _, raw := range lines {
		line := shrinkLine(raw)
		n := len(line) + 2
		if len(cur) > 0 && size+n > maxBatchBytes {
			out = append(out, cur)
			cur, size = nil, 0
		}
		cur = append(cur, line)
		size += n
	}
	if len(cur) > 0 {
		out = append(out, cur)
	}
	return out
}
