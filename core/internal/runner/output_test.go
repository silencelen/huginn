package runner

import (
	"encoding/json"
	"os"
	"path/filepath"
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
		"WORKSTATION": "workstation", "My_Box.local": "my-box-local", "--x--": "x",
		strings.Repeat("a", 50): strings.Repeat("a", 40), "": "",
	} {
		if got := MachineKey(in); got != want {
			t.Errorf("MachineKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestExecForShim(t *testing.T) {
	dir := t.TempDir()
	// A .cmd/.bat with nothing to run it safely is REFUSED, never spawned.
	for _, name := range []string{"claude.cmd", "CLAUDE.CMD", "claude.bat"} {
		if _, _, ok := execFor(filepath.Join(dir, name), []string{"-p"}, "node"); ok {
			t.Fatalf("%s with no cli.js beside it was accepted", name)
		}
	}
	// ...and redirected to node + cli.js when the shim has one beside it.
	cli := filepath.Join(dir, "node_modules", "@anthropic-ai", "claude-code", "cli.js")
	os.MkdirAll(filepath.Dir(cli), 0o755)
	os.WriteFile(cli, []byte("//"), 0o644)
	if bin, argv, ok := execFor(filepath.Join(dir, "claude.cmd"), []string{"-p"}, "node"); !ok || bin != "node" || argv[0] != cli {
		t.Fatalf("shim not redirected: %s %v %v", bin, argv, ok)
	}
	if bin, argv, ok := execFor("/x/shim.js", []string{"-p"}, "node"); !ok || bin != "node" || argv[0] != "/x/shim.js" {
		t.Fatal(".js runs through node")
	}
}

func TestRideAlongRefusal(t *testing.T) {
	for _, m := range []string{"opus&calc.exe", "opus|x", "a b", "-x", "op%PATH%", `o"p`, "^x", strings.Repeat("a", 81)} {
		if rideAlongRefusal(m, "") == "" {
			t.Errorf("model %q accepted", m)
		}
	}
	for _, m := range []string{"opus", "claude-opus-5-5", "sonnet", "local-workstation-qwen3-8b", "fable.5"} {
		if why := rideAlongRefusal(m, ""); why != "" {
			t.Errorf("model %q refused: %s", m, why)
		}
	}
	for _, e := range []string{"high&x", "HIGH", "extreme"} {
		if rideAlongRefusal("", e) == "" {
			t.Errorf("effort %q accepted", e)
		}
	}
	for _, e := range []string{"low", "medium", "high", "xhigh", "max", ""} {
		if rideAlongRefusal("", e) != "" {
			t.Errorf("effort %q refused", e)
		}
	}
}
