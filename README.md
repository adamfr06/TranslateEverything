# TranslateEverything 1.0

A client-side Fabric mod for translating Minecraft chat, signs, books, item text, titles, entity names and other game text. Version 1.0 is available for Minecraft **1.21.8** and **26.2**.



Minecraft mod that translates all text in the game, such as signs, books and quills, titles, entity names, chat, and more. Translations are cached, saved in history, and configurable.

## Version name change???
yeah...dont worry about that. This is the official 1.0. Anything released before was a...lets call it an exclusive beta. 
## Origin of the Project & General Info
I originally attempted and failed to create this project over a year ago. I was able to make a prototype for some features
but ultimately threw it away. Over the past few months I have overhauled many features, and spent lots of time testing and planning.
I then had AI refactor and optimize the mod, added AI translation, and made the gameplay experience much fore
enjoyable with niche features like input of sign and book & quill features.

AI was used in every section of the project, with its most notable contributions being in the UI and 
Immersion features. Since the **majority** of the code in this project was either by AI or assisted by AI, I will not be posting this project
to Modrinth for the time being. I may in the future post it unlisted for easier download access though. Changes to the mod will now be more maintenance oriented and will likely require less AI assistance.

Originally, this mod was intended to be for my own personal use, but after receiving feedback from multiple people on how effective
and useful this could be, I decided to make it public. 

## Basic mod guide

Open settings with **O**, or from Mod Menu. There are many more helpful keybinds you can bind to what you see fit. not all are required.

Across the app, use ISO language codes such as `en`, `es`, `cs`, or `ja`.

Whisper commands such as `/msg Steve hello`, `/w Steve hello`, `/tell Steve hello`, and `/reply hello` preserve the command and arguments. Only the message is translated, including in review. Unknown commands are untouched unless Other commands is enabled.

## Signs

Immersion replaces text in place. 

Sign editing measures the translation after a typing pause and displays line usage before you apply it. Applying a sign translation requires it to fit the four actual lines: shrinking is a local rendering effect, not a way to store more text on the server.
## Engines, cache and cost

Google, Google Cloud, LibreTranslate, Azure and AI endpoints are supported. AI can use a local model or a hosted endpoint such as OpenRouter (The features are designed for openrouter)

System → Manage cache shows the actual stored translation count. Retrying a translation stores only the updated translation.

Requests are shared where possible, with timeouts. Exact back-translations are cached and reused. Hosted endpoints receive no periodic warm-up calls (only needed for local models really). Incomplete model output is rejected rather than sent as a complete translation.

The experimental Chinese-prompt option switches the built-in forward-translation instructions to Chinese and translates custom instructions once per prompt/model/endpoint combination. The editable prompt remains in its original language; the translated copy is saved and reused. `{target}` remains a placeholder. This option is not a guarantee of lower token use, but theoretically on chinese models it should

## Planned features
- More token optimizations
- Better code readability. I hate looking at AI code...I likely will refactor the AI code using my original prototypes as refernce.
- Fix bugs as needed

If you want to learn more about the code... I had AI generate `infofromai.md`. It is an extensive 
file detailing all the files used and describes the ambiguous or hard to read methods. For the time being I will keep it updated.