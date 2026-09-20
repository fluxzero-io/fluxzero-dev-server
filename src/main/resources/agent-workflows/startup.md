## Local Demo and Startup Data

Prefer dev-server startup commands for local demo and initial development data,
rather than application startup hooks or custom seeding scripts. This keeps the
configured actions and their results visible in Devboard's Startup page. Read
the installed `fz dev config` guidance for the supported configuration and
execution semantics; use descriptive names and existing domain commands.

Use custom startup logic when the task genuinely needs behavior that configured
commands cannot express, such as transforming or dynamically assembling command
payloads. Keep that exception focused and explain why it is needed. Preserve
existing project behavior; do not migrate unrelated bootstrap code merely to
follow this preference. Production initialization is a separate concern.

After adding or changing startup data, verify its execution through the dev
server's reported startup results and check that the intended data is available
in the app. Do not infer success from configuration alone.

