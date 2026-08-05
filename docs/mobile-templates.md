# Mobile templates

`$mobile` is Abla Mobile's declarative source syntax. It parses an XML-like
tree at compile time and returns one ordinary Abla closure:

```text
FnMut(int, string) -> MobileView
```

`mobileRun` is the only runtime call an application needs. The closure captures
ordinary locals, dispatches inline actions, and rebuilds the immutable view
after every event:

```abla
var count = 0
var name = "Abla"

mobileRun($mobile
    <Screen title={"Hello $name"}>
        <Column padding={20} spacing={12}>
            <Text style="display">Count: {count}</Text>
            <Button onTap={count = count + 1}>Increment</Button>
            <TextField
                label="Name"
                value={name}
                onChange={name = value}
            />
        </Column>
    </Screen>
)
```

There is no framework state object. A template may capture any number and type
of Abla variables. Android never receives that closure or any state handle.

## Values and text

Attributes accept quoted string literals or `{Abla expressions}`. Boolean
attributes can use the bare form, so `scroll` is equivalent to `scroll={true}`.
Text content supports `{expression}` interpolation and collapses source-format
whitespace after the complete runtime string is assembled.

Container children can include a `{MobileNode}` expression. Use
`mobileGroup(arrayOfNodes)` when one expression should contribute zero or many
children; containers recursively flatten groups before encoding the tree.

## Actions and reactivity

`onTap` and `onChange` contain one or more ordinary Abla expressions separated
by semicolons:

```abla
onTap={
    count = count + 1;
    status = "Incremented in Abla"
}
```

For change events, `value` is the copied UTF-8 payload supplied by the host.
`mobilePayloadBool(value)` and `mobilePayloadInt(value, fallback)` cover the
standard controls. An action can also call any imported Abla function,
including `mobileToast`, `mobileCopyText`, `mobileOpenUrl`, and `mobileHaptic`.

The subparser assigns positive event IDs in source traversal order. Event zero
only renders; a positive event executes its matching action and then evaluates
the complete view expression again. `mobileRun` publishes that new immutable
tree, and the host replaces its Compose `StateFlow` snapshot. Stable node keys
preserve control and list identity across whole-root rerenders.

## Elements

| Element | Attributes |
| --- | --- |
| `Screen` | `title`, `dark`; exactly one root child |
| `Column` | `key`, `padding`, `spacing`, `scroll`, `visible` |
| `Row` | `key`, `spacing`, `visible` |
| `Text` | `key`, `style`, `text`, `visible` |
| `Button` | `key`, `enabled`, `appearance`, `text`, required `onTap`, `visible` |
| `TextField` | `key`, `label`, `value`, required `onChange`, `visible` |
| `Switch` | `key`, `label`, `checked`, required `onChange`, `visible` |
| `Checkbox` | `key`, `label`, `checked`, required `onChange`, `visible` |
| `Slider` | `key`, `label`, `value`, `minimum`, `maximum`, required `onChange`, `visible` |
| `Progress` | `key`, `value`, `maximum`, `circular`, `visible` |
| `Divider` | `key`, `visible` |
| `Spacer` | `key`, `height`, `visible` |
| `Card` | `key`, `title`, `visible` |
| `Chip` | `key`, `text`, `selected`, `payload`, required `onTap`, `visible` |
| `List` | `key`, `visible` |
| `If` | `condition`; contributes zero or many flattened children |

Keys default to a deterministic tag/source path. Explicit semantic keys are
recommended for controls and dynamic list items. `appearance` accepts
`filled`, `outlined`, or `text`; text styles are interpreted by the reusable
host (`body`, `label`, `headline`, and `display` are used by the showcase).

Unknown tags, unsupported or duplicate attributes, mismatched closing tags,
invalid action forms, text in non-text containers, and an invalid `Screen`
shape fail during parsing.

## Lowering boundary

The subparser lowers only to functions in `src/ui.ab`; it generates no Kotlin,
Compose source, application class, state class, getter, or callback ABI. The
only compiler addition is the framework-neutral `syntaxInferredLambda` parser
builder in `ablac`, which lets any initial-phase subparser produce a
context-typed capturing lambda. Normal semantic, ownership, capture-family,
effect, and IR validation still apply.

Splash themes, app icons, labels, and install-time permissions are Android
package configuration because they exist before or outside a rendered view.
For example, an app that performs networking declares
`android.permission.INTERNET` in its manifest while keeping HTTP/RPC calls and
response state in Abla. Fire-and-forget and result-bearing Android intents use
the copied effect/request-event boundaries described in the design spec; they
are not template nodes and never carry Abla handles.
