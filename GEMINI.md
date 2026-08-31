# Project Rules

## Pre-Push Security Audit
Before running any `git push` command, you MUST always perform a security audit of the unpushed local commits. 
To do this:
1. Run `git log origin/main..HEAD` and `git diff origin/main..HEAD` to review the pending changes.
2. Ensure there are no insecure changes such as hardcoded secrets, injection vulnerabilities, improper scoping of data, or PII leakage.
3. Only proceed with the push if the audit passes. If issues are found, alert the user and do not push.
