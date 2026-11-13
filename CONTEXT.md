# Call Spidey

A private, on-device app that lets a child place a pretend phone call to Spidey and hold a spoken conversation with him. This glossary is shared by the Android app and the iOS port.

## Language

### The call

**Call**:
One session, from the child tapping "Call Spidey" to hang-up. A Call has exactly one **Transcript**.
_Avoid_: Chat, session

**Call phase**:
Where a **Call** currently is: idle, dialing, ringing, connected, or ended.
_Avoid_: State, status

**Greeting**:
Spidey's fixed opening line when a **Call** connects, which ends by asking for the child's name.
_Avoid_: Opening, hello

**Intro**:
The scripted first **Turn** of a **Call**, in which the child's name is captured and no **Responder** is consulted.

### The conversation

**Utterance**:
One thing the child said, as text, whether it was spoken or typed.
_Avoid_: Input, message, transcription

**Reply**:
What Spidey says back to one **Utterance**. A Reply may end the **Call**.
_Avoid_: Response, answer

**Turn**:
One **Utterance** together with its **Reply**.
_Avoid_: Exchange

**Transcript**:
The ordered record of one **Call**: the **Greeting**, then every **Utterance** and **Reply**.
_Avoid_: Chat log, history

**Responder**:
Anything that produces a **Reply** for an **Utterance**. There are scripted and generative Responders, and one that combines them.
_Avoid_: Engine, bot

**Safety net**:
The guarantee that a farewell or off-limits **Utterance** is always answered by the scripted **Responder**, and that the scripted Responder answers whenever the generative one fails or says something unsuitable.
_Avoid_: Filter

### Speech

**Voice**:
The timbre Spidey speaks in.

**Speech pipeline**:
The live machinery of a connected **Call** that hears the child, turns speech into an **Utterance**, and speaks each **Reply**.
_Avoid_: Voice session

## Flagged ambiguities

- **"Transcript" vs "transcription"**: "Transcript" always means the whole record of a **Call**. The text of a single thing the child said is an **Utterance**, never a "transcription" or "transcript".
- **"Voice"**: always the timbre. The Android code also uses "voice session" for the **Speech pipeline**; that name is legacy and is not used in the iOS port.
- **"Chat"**: the Android code calls the holder of a Call's Transcript "chat state". The concept is a **Call**'s conversation; "chat" is legacy.

## Example dialogue

> **Dev:** The child mumbled and the speech recogniser returned nothing. Is that a **Turn**?
>
> **Expert:** No. An empty result isn't an **Utterance**, so there's no **Reply** and nothing is added to the **Transcript**. The **Speech pipeline** just keeps listening.
>
> **Dev:** And when she says "bye bye Spidey"?
>
> **Expert:** That's an **Utterance**, and the **Safety net** catches it: the scripted **Responder** gives the farewell **Reply**, that Reply ends the **Call**, and the **Call phase** becomes ended.
>
> **Dev:** Her very first answer, "I'm Mia", which Responder handles that?
>
> **Expert:** None. That's the **Intro**. The **Greeting** asked for her name, so the first Turn is scripted; Responders take over from the second Turn.
