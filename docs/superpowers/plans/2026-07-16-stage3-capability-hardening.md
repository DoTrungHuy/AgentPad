# Stage 3 Capability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or implement inline with TDD.

**Goal:** Make document tools produce usable working memory, allow “continue from result” as a new turn, enforce simple runtime budgets, and surface attachment permission failures — without UI redesign.

**Architecture:** Pure helpers `DocumentWorkingMemory` and `RuntimeBudget` under `agent/`; ViewModel wires them into planning/execution; minimal Compose affordances only for continue CTA and clearer errors.

**Tech Stack:** Kotlin, JUnit4, existing Compose UI hooks.
