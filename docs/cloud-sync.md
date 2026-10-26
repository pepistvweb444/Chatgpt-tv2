# Jarvis cloud sync

Base: `https://<deployment>/api/sync`, header `Authorization: Bearer <household token>`.

| Method | Path | Body / query |
|---|---|---|
| GET | /conversations | `?since=ISO` -> `{conversations:[{id,title,source,count,preview,updatedAt}]}` |
| POST | /conversations | `{id,title,source,deviceId,messages:[{role,text,at,clientMsgId}]}` |
| GET | /conversations/:id/messages | `?since=ISO` -> `{id,title,messages:[...]}` |
| POST | /conversations/:id/messages | `{deviceId, messages:[{role,text,at,clientMsgId}]}` (or a single `{role,text}`) |
| GET | /phone | `?kind=call|notification|message&since=ISO` -> `{items:[...]}` |
| POST | /phone | `{items:[{id,kind,app,title,body,contact,number,state,at,data}]}` (upsert by kind+id) |

`clientMsgId` makes message pushes idempotent (retries never duplicate).
Storage: Supabase schema `jarvis_sync` (not API-exposed, RLS on, no policies); access only via
token-checked SECURITY DEFINER functions `public.jarvis_sync_*`. Phone items auto-expire after 14 days.
Env: `JARVIS_SYNC_SUPABASE_URL`, `JARVIS_SYNC_SUPABASE_KEY` (publishable key; not a secret).
