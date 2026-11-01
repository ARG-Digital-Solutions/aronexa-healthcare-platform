# P00-T05 UI/UX acceptance specification — review draft
Authoritative contract V3.0: Option A medical design + Option D workflow efficiency.

## Tokens
Navy #102D54; Medical Blue #2563EB; Teal #0D9488; Canvas #F5F8FC; White #FFFFFF. Verify WCAG-compatible text/action contrast before implementation, not solely palette hex values. Shared typography, spacing, keyboard focus, validation and status components. No inconsistent specialty-specific navigation.

## Reception first-customer workflow
Role landing: today queue and appointment statuses, quick patient lookup with tenant/branch scoping, duplicate suggestions, walk-in registration, booking with concurrent-slot protection, check-in, cancellation/reschedule, follow-up/callback and approved payment access. Acceptance: each action available within a coherent workspace, appropriate loading/errors/empty states, keyboard/touch workflows, no unnecessary repeat data entry, data isolation.

## OPD Smart Rx workflow
One encounter workspace: patient identity, allergy/history, chief complaints, vitals, examination, diagnoses, investigations, structured medication selection, generic/brand and manual entry, doctor favourites/recent/templates, dosage/route/duration/quantity instructions, preview, doctor approval, immutable finalization, controlled amendment, PDF, follow-up. Medication suggestions do not prescribe automatically. Explicit unsaved changes and permission denial states.

## Digital ink beginning Phase 02
Reusable typed/structured plus ink overlay: mouse/finger/stylus, diagram annotations, undo/redo, eraser/clear, drafts, zoom, pressure/palm rejection only when supported, author/version history, explicit save/finalize, PDF snapshot; structured medication separate. Check reload and failed-network recovery; signature image is not digital signing assertion.

## Acceptance evidence when screens exist
Accessibility keyboard and focus, screen reader labels, responsive desktop/tablet, touch-target usability, readable errors, component contract tests, Playwright role journeys and screenshot/manual clinician review. NOT RUN until UI exists. Color choices are specified; no UI has been built by this task.
