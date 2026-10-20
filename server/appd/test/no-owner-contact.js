'use strict';

// ⚠ A TEST DAEMON NEVER REACHES THE OWNER. The suites spawn huginn-appd with
// { ...process.env }, and on a huginn host that environment can name the live
// Telegram relay (HUGINN_APPD_TELEGRAM_SCRIPT), while appd's push key defaults to
// /etc/huginn-appd/fcm-service-account.json. A test that raised an alert or a
// push would then message the owner from a scratch daemon. Requiring this
// points both at nothing for every daemon this test file starts. A test of
// delivery sets its own stand-ins in the env it spawns with, which win.
//
// node --test runs each file in its own process, so this changes only the
// current file's environment. retry-fetch.js requires it, so every route suite
// has it; no-owner-contact.test.js holds the rest to it.
process.env.HUGINN_APPD_TELEGRAM_SCRIPT = '';
// A path that cannot exist, so no account can put a key there first.
process.env.HUGINN_FCM_KEY = '/nonexistent/huginn-test-fcm-service-account.json';
