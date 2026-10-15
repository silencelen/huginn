package runner

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestSmallLineUntouched(t *testing.T) {
	l := `{"type":"x","n":12345678901234567890}`
	if shrinkLine(l) != l {
		t.Fatal("a line under the cap must pass through byte for byte")
	}
}

func TestOversizedLineKeepsItsShape(t *testing.T) {
	big := strings.Repeat("A", 400*1024)
	line := `{"type":"user","id":12345678901234567890,"message":{"content":[{"type":"tool_result","content":"` + big + `"}]}}`
	out := shrinkLine(line)
	if len(out) > maxLineBytes {
		t.Fatalf("still %d bytes", len(out))
	}
	var ev map[string]any
	dec := json.NewDecoder(strings.NewReader(out))
	dec.UseNumber()
	if err := dec.Decode(&ev); err != nil {
		t.Fatal(err)
	}
	if ev["type"] != "user" || ev["id"].(json.Number).String() != "12345678901234567890" {
		t.Fatalf("lost type or rounded the id: %v %v", ev["type"], ev["id"])
	}
	if !strings.Contains(out, "more characters were too large to send") {
		t.Fatal("no truncation marker")
	}
}

func TestUnparseableHugeLineBecomesVisibleText(t *testing.T) {
	out := shrinkLine(strings.Repeat("x", 200*1024))
	if !strings.Contains(out, `"type":"assistant"`) || !strings.Contains(out, "too large to send and was dropped") {
		t.Fatalf("got %q", out)
	}
}

func TestStructurallyHugeLine(t *testing.T) {
	// 40k tiny strings: no budget can shrink it under the cap.
	var b strings.Builder
	b.WriteString(`{"type":"user","a":[`)
	for i := 0; i < 40000; i++ {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(`"abcd"`)
	}
	b.WriteString("]}")
	out := shrinkLine(b.String())
	if !strings.Contains(out, "too large to send and was dropped") {
		t.Fatal("expected the stand-in text")
	}
}

func TestBatchesFitAndKeepOrder(t *testing.T) {
	var lines []string
	for i := 0; i < 50; i++ {
		lines = append(lines, `{"i":`+strings.Repeat(" ", 10*1024)+`0}`)
	}
	batches := batchLines(lines)
	n := 0
	for _, bt := range batches {
		size := 0
		for _, l := range bt {
			size += len(l) + 2
			n++
		}
		if size > maxBatchBytes {
			t.Fatalf("batch of %d bytes", size)
		}
	}
	if n != 50 || len(batches) < 2 {
		t.Fatalf("lines=%d batches=%d", n, len(batches))
	}
}

func TestErrWordsTakesTheHead(t *testing.T) {
	s := "node:internal/modules/cjs/loader:1228\n  throw err;\n  ^\n\nError: Cannot find module '/x/shim.js'\n    at Module._resolveFilename\n"
	if got := errWords(s); got != "Error: Cannot find module '/x/shim.js'" {
		t.Fatalf("got %q", got)
	}
	if errWords("   ") != "" || errWords("just one line") != "just one line" {
		t.Fatal("blank / single-line cases")
	}
}

func TestTextsNameTheEngine(t *testing.T) {
	if got := notFoundText("generate", "box"); !strings.HasPrefix(got, "the local engine was not found on box") || !strings.Contains(got, `"llm"`) {
		t.Fatal(got)
	}
	if got := exitedText("act", "box", 1, "Error: boom\nstack"); got != "claude exited 1 on box: Error: boom" {
		t.Fatal(got)
	}
}

func TestMachineKey(t *testing.T) {
	for in, want := range map[string]string{
		"RAGNAR": "ragnar", "My_Box.local": "my-box-local", "--x--": "x",
		strings.Repeat("a", 50): strings.Repeat("a", 40), "": "",
	} {
		if got := MachineKey(in); got != want {
			t.Errorf("MachineKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestExecForShim(t *testing.T) {
	dir := t.TempDir()
	if bin, argv := execFor(dir+"/claude.cmd", []string{"-p"}, "node"); bin != dir+"/claude.cmd" || argv[0] != "-p" {
		t.Fatal("a .cmd with no cli.js beside it is spawned as given")
	}
	if bin, argv := execFor("/x/shim.js", []string{"-p"}, "node"); bin != "node" || argv[0] != "/x/shim.js" {
		t.Fatal(".js runs through node")
	}
}
