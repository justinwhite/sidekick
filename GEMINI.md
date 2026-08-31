# Project Rules

## Pre-Push Security Audit
Before running any `git push` command, you MUST always perform a security audit of the unpushed local commits. 
To do this:
1. Run `git log origin/main..HEAD` and `git diff origin/main..HEAD` to review the pending changes.
2. Ensure there are no insecure changes such as hardcoded secrets, injection vulnerabilities, improper scoping of data, or PII leakage.
3. Only proceed with the push if the audit passes. If issues are found, alert the user and do not push.

## Pre-Push README Check
Before pushing any commits (or as part of your pre-push workflow), you MUST always check if the changes being pushed should be documented in the README.md. 
To do this:
1. Review the content of the pending changes.
2. Check the current README.md to see if it accurately reflects the new state of the codebase.
3. If there is a delta (e.g. new features, UI components, or architectural changes not mentioned in the README), update the README.md in a new commit before pushing.
