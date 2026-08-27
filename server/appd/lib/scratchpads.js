'use strict';
// Scratchpads: the user's own pages on this host, and the one way they get
// quoted into a conversation.
//
// A pad is not a chat and not a note the model keeps. It is text a PERSON writes
// and re-uses — the standing list of hostnames, the half-formed plan, the block
// of config that keeps getting pasted — held on the host so both clients see the
// same page, and attached to a message only when the person says so.
//
// Everything here is pure: the naming rules, the caps, and the two frames a pad
// is composed into. The daemon owns the files and the routes; this owns what is
// legal and what the text looks like when it arrives, so the client and the
// server cannot disagree about either.

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
// in somebody's message list. (The DEPRECATED Electron client is exempt: it is
// not built any more and never learns the marker.)

/**
 * A pad, quoted INTO a chat message.
 *
 * The content travels rather than a path because a chat runs headless with no
 * guarantee of a Read tool — an `ask` chat has none at all — so a reference that
 * named a file would be a reference the run could not follow.
 */
function chatFrame(name, content) {
  return `[Scratchpad "${name}"]\n${content}\n[End scratchpad]`;
}

function composeForChat(pad, text) {
  return `${chatFrame(pad.name, String(pad.content || ''))}\n\n${text}`;
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

/** Newest edit first, except that Main is always the first row. */
function sortPads(pads) {
  return [...pads].sort((a, b) => {
    if (isMain(a) !== isMain(b)) return isMain(a) ? -1 : 1;
    return (b.updatedAt || 0) - (a.updatedAt || 0);
  });
}

module.exports = {
  MAIN_NAME, MAX_NAME, MAX_CONTENT, MAX_PADS,
  cleanName, nameProblem, contentProblem, isMain,
  chatFrame, composeForChat, sessionFrame, composeForSession,
  fitProblem, padRow, sortPads,
};
