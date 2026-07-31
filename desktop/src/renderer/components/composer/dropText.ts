// What a dropped run of text does to the draft already in the box.
//
// Appending, not replacing: a drop is an addition to what you were writing, and
// replacing would silently destroy a draft that the app otherwise persists very
// carefully. The separator is a newline unless the draft already ends in
// whitespace, so dropping a path onto "look at " puts it where the sentence was
// going rather than on a line of its own.

/** Normalised, appended. Returns `current` unchanged when there is nothing to add. */
export function appendDropped(current: string, dropped: string): string {
  // Trailing whitespace comes free with most drag sources and is never wanted;
  // leading whitespace can be real indentation in a dropped code fragment.
  const add = dropped.replace(/\r\n?/g, '\n').replace(/\s+$/, '')
  if (add === '') return current
  if (current === '') return add
  return /\s$/.test(current) ? current + add : `${current}\n${add}`
}
