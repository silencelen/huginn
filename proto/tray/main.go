// Prototype Q9 (docs/CORE-ARCHITECTURE.md §11): can a pure-Go (CGO_ENABLED=0)
// tray icon register with the desktop's StatusNotifierWatcher (Linux) / the
// Win32 notification area (Windows)? Shows one labelled icon for N seconds,
// then exits. Not product code.
package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"strconv"
	"time"

	"fyne.io/systray"
)

func main() {
	secs := 20
	if len(os.Args) > 1 {
		secs, _ = strconv.Atoi(os.Args[1])
	}
	systray.Run(func() {
		systray.SetIcon(icon())
		systray.SetTitle("huginn")
		systray.SetTooltip("huginn core prototype (tray test, exits by itself)")
		open := systray.AddMenuItem("Open Huginn (prototype)", "")
		systray.AddSeparator()
		quit := systray.AddMenuItem("Quit prototype", "")
		go func() {
			for {
				select {
				case <-open.ClickedCh:
					fmt.Println("menu: open clicked")
				case <-quit.ClickedCh:
					systray.Quit()
				}
			}
		}()
		fmt.Println("tray: ready")
		time.AfterFunc(time.Duration(secs)*time.Second, systray.Quit)
	}, func() { fmt.Println("tray: exited") })
}

// icon draws a 32x32 heather disc (BRAND.md info heather #B49CDB); ICO on
// Windows wraps the same PNG.
func icon() []byte {
	img := image.NewNRGBA(image.Rect(0, 0, 32, 32))
	for y := 0; y < 32; y++ {
		for x := 0; x < 32; x++ {
			dx, dy := x-16, y-16
			if dx*dx+dy*dy <= 196 {
				img.Set(x, y, color.NRGBA{0xB4, 0x9C, 0xDB, 0xFF})
			}
		}
	}
	var b bytes.Buffer
	png.Encode(&b, img)
	if !isWindows {
		return b.Bytes()
	}
	// ICO container: header + one directory entry pointing at the PNG.
	var ico bytes.Buffer
	binary.Write(&ico, binary.LittleEndian, []uint16{0, 1, 1})
	ico.Write([]byte{32, 32, 0, 0})
	binary.Write(&ico, binary.LittleEndian, []uint16{1, 32})
	binary.Write(&ico, binary.LittleEndian, []uint32{uint32(b.Len()), 22})
	ico.Write(b.Bytes())
	return ico.Bytes()
}
