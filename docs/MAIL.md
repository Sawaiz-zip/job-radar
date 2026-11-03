# Reading replies to your applications

Job Radar can read your mailbox and show, for each application, whether the reply was a
rejection, an invitation or just an acknowledgement. Choose the route with
`jobradar.mail.provider` (`gmail` or `imap`). Secrets go in a `.env` file in the project
root, which is git-ignored and loaded on start.

## Gmail (OAuth)

A plain API key cannot read a mailbox, because mail is per-user data, so Google requires
OAuth. Using the API is free. Setup, once:

1. In the [Google Cloud console](https://console.cloud.google.com), create a project.
2. **APIs & Services → Library**: enable the **Gmail API**.
3. **OAuth consent screen**: choose *External*, fill in the app name and your email, and
   add the mailbox you want to read under **Test users**. Skipping that step is what
   produces `access_denied` on the consent screen.
4. **Credentials → Create credentials → OAuth client ID**: choose *Web application* and add
   `http://localhost:8090/api/v1/gmail/callback` as an authorised redirect URI.
5. Put the two values in `.env`:
   ```
   GMAIL_CLIENT_ID=...apps.googleusercontent.com
   GMAIL_CLIENT_SECRET=...
   ```
6. Restart Job Radar, open **Applied**, click **Connect Gmail** and grant read-only access.

The scope is `gmail.readonly`, consent happens in your own browser, and Job Radar stores
only the refresh token. **Disconnect** deletes it.

**The seven-day limit.** `gmail.readonly` is a restricted scope, and an app left in
*Testing* mode has its refresh token expired by Google after seven days. Job Radar
recognises that failure and asks you to click **Connect Gmail** again, instead of showing
`invalid_grant`.

## IMAP

Set `jobradar.mail.provider: imap` and add to `.env`:

```
IMAP_USER=you@example.com
IMAP_PASSWORD=your-app-password
```

Use an app password, not your account password (Gmail: *Google Account → Security → App
passwords*). Change `jobradar.imap.host` for other providers. There is no Google Cloud
project and no expiry, at the cost of keeping a credential in the file.

## How replies are matched and classified

The mailbox is read once per sync and matched locally. Matching uses the sender's domain
first, since an ATS mails on the employer's behalf and names them in the subject, and it
refuses to match on a company name shorter than four characters, which would otherwise
catch any passing newsletter.

Classification is keyword-based, in English and German. Anything the keywords cannot settle
is stored as `UNCLEAR` with the subject attached rather than guessed at: a wrong "rejected"
is worse than no answer.
