# Validexia Risk Scoring Contract

**Version:** 1.0 | **Date:** 2026-10-09 | **Status:** Design note. This document defines the
scoring semantics used by the web interface. The backend does not currently emit risk
scores; how backend scoring will be produced and reconciled with these semantics is an
open design decision.

## The critical rule

> **In the Validexia interface, a HIGHER score means HIGHER risk.**
> Scale: 0–100. A vendor scoring 92 is riskier than one scoring 18.

Some scoring systems use the opposite convention (for example, a 0–1000 scale where a
higher number means safer). Any future backend score must be reconciled with the rule
above before it is displayed: wiring an inverted score into the interface would show
safe vendors as critical and critical vendors as safe.

## Score definitions

| Score | Meaning | Range |
|---|---|---|
| **Inherent risk** | Danger BEFORE any controls/mitigations. Based on data accessed, business criticality, substitutability. | 0–100, higher = riskier |
| **Residual risk** | Danger AFTER controls, contracts, and monitoring are applied. This is the primary displayed score. | 0–100, higher = riskier |
| **Portfolio health** | `100 − Σ penalties`. Higher = healthier portfolio. | 0–100, higher = healthier |

## Risk bands (residual risk)

| Band | Range | Color |
|---|---|---|
| Critical | 80–100 | Red |
| High | 60–79 | Orange |
| Moderate | 30–59 | Yellow |
| Low | 0–29 | Green |

## Inherent vs residual relationship

- `inherentRisk >= residualRisk` should always hold. Controls reduce risk, never increase it.
- A large gap (inherent 95 → residual 40) means controls are working.
- A small gap (inherent 90 → residual 88) means the vendor is dangerous AND poorly controlled — highest priority.

## Evidence confidence

Evidence does not change the risk score directly. Instead:
- Missing/expired evidence adds a **penalty kicker** to portfolio health (+5 per missing).
- Evidence confidence (High/Medium/Low) is displayed alongside the score as a qualifier.
- A high risk score with low evidence confidence = "we think it's bad but we're not sure" — needs assessment, not action.

## What the backend must provide

Before integration, the backend must confirm in writing:
1. Score direction (higher = safer or riskier?)
2. Scale (0–100? 0–1000? A–F?)
3. Whether it provides inherent, residual, or a single blended score
4. Score version/model (for audit trail: "which model produced this number?")
5. How often scores recompute and what triggers recomputation
