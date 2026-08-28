'use strict';
// Scratchpads: the user's own pages on this host, and the one way they get
// quoted into a conversation.
//
// A pad is not a chat and not a note the model keeps. It is text a PERSON writes
// and re-uses — the standing list of hostnames, the half-formed plan, the block
// of config that keeps getting pasted — held on the host so both clients see the
// same page, and attached to a message only when the person says so.
//
// Everything here is pure but one thing: the naming rules, the caps, and the two
// frames a pad is composed into. The daemon owns the files and the routes; this
// owns what is legal and what the text looks like when it arrives, so the client
// and the server cannot disagree about either. (The exception is the frame tag —
// see [chatFrame] — which is random by necessity and injectable so a test can
// still assert a literal.)

const crypto = require('node:crypto');

/** The pad every install has, and the one a reference falls back to. */
const MAIN_NAME = 'Main';

const MAX_NAME = 60;
const MAX_CONTENT = 100_000;
/**
 * How many pads one host may hold.
 *
 * A number rather than no limit because the list is fetched whole on a 5-second
 * poll and every pad is one file: this is the point past which "a few pages" has
 * become a filesystem somebody is keeping in a picker.
 */
const MAX_PADS = 64;

// C0 and C1 controls plus DEL. A pad NAME is shown on one line in a picker, a
// chip and a rail tooltip, and a terminal is a renderer that executes some of
// what it is handed — the same argument rounds.js makes about a report headline,
// for the same reason: this text ends up on somebody's screen through more than
// one path.
const CTRL_ALL = /[\u0000-\u001f\u007f-\u009f]/g;

/** A pad name as it will actually be stored: one line, no controls, trimmed. */
function cleanName(raw) {
  if (typeof raw !== 'string') return '';
  return raw.replace(CTRL_ALL, ' ').replace(/\s+/g, ' ').trim().slice(0, MAX_NAME);
}

/**
 * Why this name cannot be used, or null.
 *
 * @param taken the names already in use, for the uniqueness check. Case
 *   INSENSITIVE: "notes" and "Notes" are the same page as far as anyone
 *   picking one out of a list is concerned, and two rows that read the same is
 *   how the wrong pad gets attached to a message.
 */
function nameProblem(raw, taken = []) {
  const name = cleanName(raw);
  if (!name) return 'a page needs a name';
  // ⚠ THE DOUBLE QUOTE IS THE MARKER'S OWN DELIMITER. The frame a pad is quoted
  // into writes [Scratchpad "«name»"], and both clients collapse that back to a
  // pill by matching it — so a name carrying a quote would end the marker early
  // and leave the rest of it sitting in the user's own message as raw text.
  // Refused rather than silently stripped: a page called Ideas "v2" that came
  // back named Ideas v2 is an edit nobody asked for.
  if (name.includes('"')) return 'a page name cannot contain a double quote';
  if (String(raw).replace(CTRL_ALL, ' ').replace(/\s+/g, ' ').trim().length > MAX_NAME) {
    return `a page name is at most ${MAX_NAME} characters`;
  }
  const lower = name.toLowerCase();
  if (taken.some((t) => cleanName(t).toLowerCase() === lower)) return 'there is already a page with that name';
  return null;
}

/** Why this content cannot be stored, or null. */
function contentProblem(raw) {
  if (typeof raw !== 'string') return 'content must be text';
  if (raw.length > MAX_CONTENT) return `a page holds at most ${MAX_CONTENT.toLocaleString('en-US')} characters`;
  return null;
}

/** Whether this pad is the undeletable, unrenameable one. */
function isMain(pad) {
  return !!(pad && pad.main === true);
}

// ------------------------------------------------------------------- frames
//
// ⚠ THE TWO LITERALS BELOW HAVE COPIES. :core's ScratchpadRules collapses them
// back into a pill so the sender reads their own message rather than the page
// they attached, and humanizeUserText in the daemon does the same for a chat
// title. A wording change here that is not made there leaves a raw frame sitting
// in somebody's message list.
//
// The tagged variant is part of that contract. Both collapsers match, exactly:
//
//     open   \[Scratchpad "([^"\n]{1,60})"( #[0-9a-f]{6})?\]
//     close  \[End scratchpad( #[0-9a-f]{6})?\]
//
// with the SAME tag required on both ends — which is what makes a tagged frame
// survive a pasted `[End scratchpad]` sitting in the middle of the page.

/**
 * A line of the pad's own CONTENT that would close the frame around it.
 *
 * Line-anchored because that is exactly what the collapsers match: a mention of
 * the marker mid-sentence is harmless, a line that STARTS with it is the end of
 * the frame as far as every reader is concerned.
 */
const CONTENT_CLOSES_FRAME = /^\[End scratchpad/m;

/** Six lowercase hex, the report-tag precedent at lib/rounds.js's own size/3. */
function mintTag() {
  return crypto.randomBytes(3).toString('hex');
}

/**
 * A pad, quoted INTO a chat message.
 *
 * The content travels rather than a path because a chat runs headless with no
 * guarantee of a Read tool — an `ask` chat has none at all — so a reference that
 * named a file would be a reference the run could not follow.
 *
 * ⚠ A PAGE CAN CONTAIN ITS OWN CLOSING MARKER, and pages of pasted conversation
 * routinely do. An untagged frame around content holding a line that begins
 * `[End scratchpad` ends THERE: the run reads half a page, and both collapsers
 * turn the rest of it into raw text sitting in the sender's own message. The
 * answer is the one rounds.js already uses for a report block found in fetched
 * content — a tag minted here and carried on BOTH markers, so the close that
 * matters is the one that matches the open.
 *
 * Minted only when the content forces it, so the ordinary frame stays the exact
 * literal three other files quote. `tag` is injectable so a test can assert one.
 */
function chatFrame(name, content, tag = null) {
  const body = String(content ?? '');
  if (!CONTENT_CLOSES_FRAME.test(body)) return `[Scratchpad "${name}"]\n${body}\n[End scratchpad]`;
  // A pasted frame in the content may already carry a tag; re-minted rather than
  // trusted to be unique, because a collision would close this frame early in
  // exactly the case the tag exists to prevent.
  let t = tag || mintTag();
  for (let i = 0; !tag && i < 8 && body.includes(t); i++) t = mintTag();
  return `[Scratchpad "${name}" #${t}]\n${body}\n[End scratchpad #${t}]`;
}

function composeForChat(pad, text, tag = null) {
  return `${chatFrame(pad.name, String(pad.content || ''), tag)}\n\n${text}`;
}

/**
 * The same, for a tmux session — a PATH, not the text.
 *
 * A session's composer is `tmux send-keys` into a live pane with an 8,000
 * character cap, and the pad is the one thing in the message that can be
 * arbitrarily long. Claude in that pane has the Read tool and the file is on the
 * same host, so naming it is both shorter and more useful than pasting it: the
 * run can re-read the page as it changes rather than holding one snapshot.
 */
function sessionFrame(name, filePath) {
  return `[Scratchpad "${name}" at ${filePath} — read it before acting on this message.]`;
}

function composeForSession(pad, filePath, text) {
  return `${sessionFrame(pad.name, filePath)}\n${text}`;
}

/**
 * Why the composed message will not fit, or null.
 *
 * Checked BEFORE anything is sent, and phrased about the page rather than about
 * the message: the person typed forty words and is being refused because of a
 * page they attached, and "text too long" about their forty words reads as a bug.
 */
function fitProblem(composed, cap) {
  if (composed.length <= cap) return null;
  return `that page and this message come to ${composed.length.toLocaleString('en-US')} characters, `
    + `over the ${cap.toLocaleString('en-US')} limit — shorten one of them`;
}

/**
 * The same, for the SESSION path — which needs its own wording, not this one's.
 *
 * ⚠ A DIFFERENT THING TRAVELS ON THAT PATH. The page itself is not in the
 * message; only [sessionFrame]'s one line naming a file is. So "that page and
 * this message come to N characters — shorten one of them" named the wrong
 * culprit and offered a fix that cannot work: shortening a 90,000-character page
 * changes the composed length by nothing at all. The only thing the sender can
 * shorten is what they typed, so the number they are given is how much room is
 * left for it once the reference has taken its share.
 */
function sessionFitProblem(composed, frame, cap) {
  if (composed.length <= cap) return null;
  const room = Math.max(0, cap - frame.length - 1);        // the newline that joins them
  return `the one-line reference to that page takes ${frame.length.toLocaleString('en-US')} of the `
    + `${cap.toLocaleString('en-US')} characters a session accepts, leaving room for `
    + `${room.toLocaleString('en-US')} — this message is `
    + `${Math.max(0, composed.length - frame.length - 1).toLocaleString('en-US')}`;
}

// --------------------------------------------------------------------- views

/** A list row: everything but the content, which is what makes the list cheap. */
function padRow(pad) {
  return {
    id: pad.id,
    name: pad.name,
    createdAt: pad.createdAt ?? 0,
    updatedAt: pad.updatedAt ?? 0,
    rev: pad.rev ?? 0,
    main: isMain(pad),
    // The one fact about the content worth having without fetching it: a picker
    // that cannot tell a written page from an empty one is a picker that attaches
    // nothing and says it attached something.
    size: String(pad.content || '').length,
  };
}

/**
 * Main first, then by NAME, case-insensitively.
 *
 * ⚠ IT USED TO BE NEWEST-EDIT-FIRST, and that is a list that reorders itself
 * under the person using it: typing into a page moves it to the top, so the row
 * the cursor was in is now somewhere else and the next click lands on a
 * different page. Observed twice in one sitting — text typed into the wrong pad
 * both times. A picker is a place, and a place has to stay put; recency belongs
 * in the row (`updatedAt` is on it), not in the ordering.
 *
 * Plain code-unit comparison on the lowercased name rather than localeCompare:
 * both clients sort the same list with the same rule, and a locale-aware collator
 * would give one of them a different answer for the same two rows. The id
 * breaks a tie so the order is total — two pages CAN read the same, because
 * uniqueness is enforced at create time and a page renamed on one device races a
 * page created on another.
 */
function sortPads(pads) {
  return [...pads].sort((a, b) => {
    if (isMain(a) !== isMain(b)) return isMain(a) ? -1 : 1;
    const an = String(a.name || '').toLowerCase();
    const bn = String(b.name || '').toLowerCase();
    if (an !== bn) return an < bn ? -1 : 1;
    return String(a.id || '') < String(b.id || '') ? -1 : 1;
  });
}

module.exports = {
  MAIN_NAME, MAX_NAME, MAX_CONTENT, MAX_PADS,
  cleanName, nameProblem, contentProblem, isMain,
  chatFrame, composeForChat, sessionFrame, composeForSession,
  fitProblem, sessionFitProblem, padRow, sortPads,
};
