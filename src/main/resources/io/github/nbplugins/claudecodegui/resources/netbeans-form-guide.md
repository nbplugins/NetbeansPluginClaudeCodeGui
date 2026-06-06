# NetBeans Form Design Guide

Comprehensive rules for creating valid NetBeans GUI Designer `.form` + `.java` file pairs.
Derived from Apache NetBeans source: `GandalfPersistenceManager`, `ColorEditor`, `FontEditor`, `BorderEditor`, layout support delegates, and real `.form` samples.

---

## 1. File Creation Rules

### Always create TWO files per form class
| File | Purpose |
|------|---------|
| `ClassName.java` | Java source with `GEN-BEGIN/END` markers |
| `ClassName.form` | XML design descriptor (Matisse/Gandalf format) |

### Core rule — .java and .form must stay in sync
Every UI element declared in `.form` must have a matching variable in `GEN-BEGIN:variables` and matching init code in `GEN-BEGIN:initComponents`. A mismatch causes the Design view to fail loading.

### Package / folder rule
- Files should be in a `views` package (`.../views/ClassName.java`) for best visibility in the NetBeans Projects panel.
- Maven projects always show `.form` as a visible separate node regardless of package — this is normal cosmetic behaviour, not an error.
- Ant-based projects always hide `.form` nodes — also normal.

---

## 2. Mandatory .java Structure

### JFrame
```java
package com.example.views;

public class MyFrame extends javax.swing.JFrame {

    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(MyFrame.class.getName());

    public MyFrame() {
        initComponents();
        setupComponents();
    }

    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc=" Generated Code ">//GEN-BEGIN:initComponents
    private void initComponents() {
        myButton = new javax.swing.JButton();  // alphabetical order
        myLabel = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        myButton.setText("Click Me");
        myButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                myButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(myLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 400, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(myLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 300, Short.MAX_VALUE)
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void myButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_myButtonActionPerformed
        // handler body
    }//GEN-LAST:event_myButtonActionPerformed

    private void setupComponents() {
        // complex wiring outside GEN block: GridBagConstraints, custom models, etc.
    }

    public static void main(String args[]) {
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ex) {
            logger.log(java.util.logging.Level.SEVERE, null, ex);
        }
        java.awt.EventQueue.invokeLater(() -> new MyFrame().setVisible(true));
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton myButton;
    private javax.swing.JLabel myLabel;
    // End of variables declaration//GEN-END:variables
}
```

### JPanel
```java
package com.example.views;

public class MyPanel extends javax.swing.JPanel {

    public MyPanel() {
        initComponents();
        setupComponents();
    }

    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc=" Generated Code ">//GEN-BEGIN:initComponents
    private void initComponents() {
        myLabel = new javax.swing.JLabel();

        myLabel.setText("Hello");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(myLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 400, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(myLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 300, Short.MAX_VALUE)
        );
    }// </editor-fold>//GEN-END:initComponents

    private void setupComponents() { }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel myLabel;
    // End of variables declaration//GEN-END:variables
}
```

**Critical markers** — without these the Design tab will NOT open:
- `//GEN-BEGIN:initComponents` … `//GEN-END:initComponents`
- `//GEN-BEGIN:variables` … `//GEN-END:variables`
- All types must be **fully qualified** (`javax.swing.JLabel`, not `JLabel`)

### ⚠️ Official template rules (from NetBeans source templates)

**`editor-fold` desc must have spaces:** `desc=" Generated Code "` — space before and after.

**Two-phase `initComponents()` order:**
1. All `new` instantiations — alphabetical order by variable name
2. Blank line
3. Root component properties (`setDefaultCloseOperation`, `setTitle`, etc.)
4. Per-component property setters and event listeners
5. GroupLayout setup
6. `setJMenuBar(...)` if applicable
7. `pack()` — last (JFrame/JDialog/JInternalFrame only)

**Variables block is always alphabetically sorted.**

**JPanel/JInternalFrame use `this`**, not `getContentPane()`:
```java
javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
this.setLayout(layout);
```

**JDialog constructor signature:**
```java
public MyDialog(java.awt.Frame parent, boolean modal) {
    super(parent, modal);
    initComponents();
}
```
JDialog default close operation is `DISPOSE_ON_CLOSE` (`value="2"` in `.form`), not `EXIT_ON_CLOSE`.

**Empty frame uses `addGap`:**
```java
layout.setHorizontalGroup(
    layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
    .addGap(0, 400, Short.MAX_VALUE)
);
```

---

## 3. GEN Block Rules — What Is Forbidden

The NetBeans parser is strict. Violations cause **"cannot find sections with generated code"**.

| ❌ NOT allowed inside GEN block | ✅ Move to `setupComponents()` |
|---|---|
| `GridBagConstraints gbc = new GridBagConstraints()` | Move entirely |
| `new MyCustomClass()` or any inner class instantiation | Move entirely |
| `tableModel = new MyTableModel()` | Move entirely |
| `cmbBox.setModel(new DefaultComboBoxModel<>(...))` | Move entirely |
| Multi-line GridBagLayout wiring | Move entirely |
| `GEN-FIRST`/`GEN-LAST` inside anonymous listener body | Move to standalone method |

### Correct GEN-FIRST/GEN-LAST pattern

`GEN-FIRST`/`GEN-LAST` go on **standalone private methods placed AFTER `initComponents()`**, never inside anonymous listeners.

**❌ WRONG:**
```java
btn.addActionListener(new java.awt.event.ActionListener() {
    public void actionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnActionPerformed
        doSomething();
    }//GEN-LAST:event_btnActionPerformed
});
```

**✅ CORRECT:**
```java
// Inside initComponents() — plain delegation, NO GEN markers:
btn.addActionListener(new java.awt.event.ActionListener() {
    public void actionPerformed(java.awt.event.ActionEvent evt) {
        btnActionPerformed(evt);
    }
});

// Standalone method after initComponents():
private void btnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnActionPerformed
    doSomething();
}//GEN-LAST:event_btnActionPerformed
```

The `handler=` attribute in `.form` XML must match the standalone method name exactly.

---

## 4. .form XML — Root Element & Form Types

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<Form version="1.3" maxVersion="1.9" type="org.netbeans.modules.form.forminfo.JFrameFormInfo">
  ...
</Form>
```

### Form type attribute
| Class extends | `type=` value |
|---|---|
| `javax.swing.JFrame` | `org.netbeans.modules.form.forminfo.JFrameFormInfo` |
| `javax.swing.JPanel` | `org.netbeans.modules.form.forminfo.JPanelFormInfo` |
| `javax.swing.JDialog` | `org.netbeans.modules.form.forminfo.JDialogFormInfo` |
| `javax.swing.JInternalFrame` | `org.netbeans.modules.form.forminfo.JInternalFrameFormInfo` |

When `type=` is **omitted** (e.g., `<Form version="1.4" maxVersion="1.4">`), the form's top-level component is treated as a `JPanel`-like container (used for non-visual component panels stored in `NonVisualComponents`).

### Version numbers
The `version` attribute records the format version used when the file was saved; `maxVersion` is the highest format the file requires to load correctly. NetBeans 12+ writes `version="1.3"` or higher for GroupLayout forms and up to `maxVersion="1.9"` for newer features. Safe defaults: `version="1.3" maxVersion="1.9"`.

---

## 5. Top-level XML Sections (order matters)

```xml
<Form ...>
  <NonVisualComponents>  <!-- ButtonGroup, JMenuBar, helper JPanels not on screen -->
  </NonVisualComponents>
  <Properties>           <!-- root component properties (e.g. defaultCloseOperation) -->
  </Properties>
  <SyntheticProperties>  <!-- NetBeans-only metadata (menuBar link, size policy, etc.) -->
  </SyntheticProperties>
  <Events>               <!-- root component events (e.g. windowClosing) -->
  </Events>
  <AuxValues>            <!-- form settings (code generation flags) -->
  </AuxValues>
  <Layout>               <!-- layout of root container -->
  </Layout>
  <SubComponents>        <!-- visible components / containers -->
  </SubComponents>
</Form>
```

### NonVisualComponents vs SubComponents
- **`NonVisualComponents`** — components that exist in the Java class but are not placed on the visual canvas: `ButtonGroup`, `JMenuBar` (linked via `SyntheticProperty`), helper `JPanel`s, timers, etc.
- **`SubComponents`** — all visual components/containers that appear on the form at design time.

---

## 6. SyntheticProperties

`SyntheticProperties` are NetBeans-internal metadata that drive code generation but are not real bean properties.

```xml
<SyntheticProperties>
  <SyntheticProperty name="menuBar"          type="java.lang.String"  value="jMenuBar1"/>
  <SyntheticProperty name="formSizePolicy"   type="int"               value="1"/>
  <SyntheticProperty name="generateCenter"   type="boolean"           value="false"/>
</SyntheticProperties>
```

| Name | Type | Values / Meaning |
|------|------|-----------------|
| `menuBar` | `java.lang.String` | Name of the `JMenuBar` component declared in `NonVisualComponents` |
| `formSizePolicy` | `int` | `1` = call `pack()` (preferred); `2` = use fixed designer size |
| `generateCenter` | `boolean` | `true` = call `setLocationRelativeTo(null)` to center on screen |
| `designerSize` | `java.awt.Dimension` | Serialized byte array of the designer canvas size — do not hand-edit |

---

## 7. AuxValues (FormSettings)

All standard `AuxValues` for a new form:

```xml
<AuxValues>
  <AuxValue name="FormSettings_autoResourcing"       type="java.lang.Integer" value="0"/>
  <AuxValue name="FormSettings_autoSetComponentName" type="java.lang.Boolean" value="false"/>
  <AuxValue name="FormSettings_generateFQN"          type="java.lang.Boolean" value="true"/>
  <AuxValue name="FormSettings_generateMnemonicsCode" type="java.lang.Boolean" value="false"/>
  <AuxValue name="FormSettings_i18nAutoMode"         type="java.lang.Boolean" value="false"/>
  <AuxValue name="FormSettings_layoutCodeTarget"     type="java.lang.Integer" value="1"/>
  <AuxValue name="FormSettings_listenerGenerationStyle" type="java.lang.Integer" value="0"/>
  <AuxValue name="FormSettings_variablesLocal"       type="java.lang.Boolean" value="false"/>
  <AuxValue name="FormSettings_variablesModifier"    type="java.lang.Integer" value="2"/>
</AuxValues>
```

### variablesModifier values
| Value | Java modifier |
|-------|--------------|
| `0`   | package-private (no modifier) |
| `2`   | `private` ✅ (recommended) |
| `4`   | `protected` |

### listenerGenerationStyle values
| Value | Style |
|-------|-------|
| `0`   | Anonymous inner classes |
| `1`   | Inner class (one shared listener) |
| `3`   | Lambda expressions |

---

## 8. Layout — GroupLayout (default for new forms)

GroupLayout is the default since NetBeans 5.5. The `.form` XML encodes it as two `DimensionLayout` elements (dim="0" = horizontal, dim="1" = vertical).

### Full-frame single component
```xml
<Layout>
  <DimensionLayout dim="0">
    <Group type="103" groupAlignment="0" attributes="0">
        <Component id="myLabel" alignment="0" max="32767" attributes="0"/>
    </Group>
  </DimensionLayout>
  <DimensionLayout dim="1">
    <Group type="103" groupAlignment="0" attributes="0">
        <Component id="myLabel" alignment="0" max="32767" attributes="0"/>
    </Group>
  </DimensionLayout>
</Layout>
```

### Empty frame (no components)
```xml
<Layout>
  <DimensionLayout dim="0">
    <Group type="103" groupAlignment="0" attributes="0">
        <EmptySpace min="0" pref="400" max="32767" attributes="0"/>
    </Group>
  </DimensionLayout>
  <DimensionLayout dim="1">
    <Group type="103" groupAlignment="0" attributes="0">
        <EmptySpace min="0" pref="300" max="32767" attributes="0"/>
    </Group>
  </DimensionLayout>
</Layout>
```

### GroupLayout XML element reference
| Element | Key attributes | Meaning |
|---------|---------------|---------|
| `Group` | `type="103"` | Parallel group |
| `Group` | `type="102"` | Sequential group |
| `Component` | `id`, `min`, `pref`, `max` | Component size in group; `max="32767"` = `Short.MAX_VALUE` = grow |
| `EmptySpace` | `min`, `pref`, `max` | Gap / padding; `-2` = default gap |
| `Group` | `groupAlignment="0"` | LEADING; `"1"`= TRAILING; `"2"`= CENTER; `"3"`= BASELINE |

### Matching Java code
```java
javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
getContentPane().setLayout(layout);
layout.setHorizontalGroup(
    layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
    .addComponent(myLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 800, Short.MAX_VALUE)
);
layout.setVerticalGroup(
    layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
    .addComponent(myLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 400, Short.MAX_VALUE)
);
```

---

## 9. Layout — Other Layout Managers

### BorderLayout
```xml
<Layout class="org.netbeans.modules.form.compat2.layouts.DesignBorderLayout"/>
<SubComponents>
  <Container class="javax.swing.JPanel" name="mainPanel">
    <Constraints>
      <Constraint layoutClass="org.netbeans.modules.form.compat2.layouts.DesignBorderLayout"
                  value="org.netbeans.modules.form.compat2.layouts.DesignBorderLayout$BorderConstraintsDescription">
        <BorderConstraints direction="Center"/>
      </Constraint>
    </Constraints>
    ...
  </Container>
</SubComponents>
```

`direction` values: `"Center"`, `"North"`, `"South"`, `"East"`, `"West"`

Java equivalent: `add(mainPanel, java.awt.BorderLayout.CENTER);`

### GridBagLayout
```xml
<Layout class="org.netbeans.modules.form.compat2.layouts.DesignGridBagLayout"/>
<SubComponents>
  <Component class="javax.swing.JLabel" name="myLabel">
    <Constraints>
      <Constraint layoutClass="org.netbeans.modules.form.compat2.layouts.DesignGridBagLayout"
                  value="org.netbeans.modules.form.compat2.layouts.DesignGridBagLayout$GridBagConstraintsDescription">
        <GridBagConstraints gridX="-1" gridY="-1" gridWidth="1" gridHeight="1"
                            fill="2" ipadX="0" ipadY="0"
                            insetsTop="0" insetsLeft="0" insetsBottom="6" insetsRight="6"
                            anchor="17" weightX="0.0" weightY="0.0"/>
      </Constraint>
    </Constraints>
  </Component>
</SubComponents>
```

`fill` values: `0`=NONE, `1`=BOTH, `2`=HORIZONTAL, `3`=VERTICAL  
`anchor` values: `10`=CENTER, `17`=EAST (LINE_END), `13`=WEST (LINE_START), `11`=NORTH, `15`=SOUTH

### FlowLayout
```xml
<Layout class="org.netbeans.modules.form.compat2.layouts.DesignFlowLayout"/>
```
No constraints needed — components are added in order.

### JScrollPane layout (single child)
```xml
<Container class="javax.swing.JScrollPane" name="scrollPane">
  <AuxValues>
    <AuxValue name="autoScrollPane" type="java.lang.Boolean" value="true"/>
  </AuxValues>
  <Layout class="org.netbeans.modules.form.compat2.layouts.support.JScrollPaneSupportLayout"/>
  <SubComponents>
    <Component class="javax.swing.JList" name="myList">
      ...
    </Component>
  </SubComponents>
</Container>
```

- `autoScrollPane="true"` means NetBeans auto-wrapped the child in a scroll pane from the palette.
- `JScrollPaneSupportLayout` accepts **exactly one** child; adding more is undefined.
- Java equivalent: `scrollPane.setViewportView(myList);`

### JTabbedPane layout
```xml
<Container class="javax.swing.JTabbedPane" name="tabbedPane">
  <Layout class="org.netbeans.modules.form.compat2.layouts.support.JTabbedPaneSupportLayout"/>
  <SubComponents>
    <Container class="javax.swing.JPanel" name="tab1Panel">
      <Constraints>
        <Constraint layoutClass="org.netbeans.modules.form.compat2.layouts.support.JTabbedPaneSupportLayout"
                    value="org.netbeans.modules.form.compat2.layouts.support.JTabbedPaneSupportLayout$JTabbedPaneConstraintsDescription">
          <JTabbedPaneConstraints tabName="Tab 1">
            <Property name="tabTitle" type="java.lang.String" value="Tab 1"/>
          </JTabbedPaneConstraints>
        </Constraint>
      </Constraints>
      <Layout>...</Layout>
      <SubComponents>...</SubComponents>
    </Container>
  </SubComponents>
</Container>
```

Java equivalent: `tabbedPane.addTab("Tab 1", tab1Panel);`

---

## 10. Color Properties — ⚠️ CRITICAL

`ColorEditor.readFromXML()` calls `Integer.parseInt(value, 16)` — values are **lowercase hex strings**, never decimal, never float.

```xml
<Property name="foreground" type="java.awt.Color" editor="org.netbeans.beaninfo.editors.ColorEditor">
  <Color red="HH" green="HH" blue="HH" type="rgb"/>
</Property>
```

Applies to: `background`, `foreground`, `caretColor`, `selectionColor`, `selectionBackground`, etc.

### Color table
| Color | red | green | blue |
|-------|-----|-------|------|
| Black (0,0,0) | `0` | `0` | `0` |
| White (255,255,255) | `ff` | `ff` | `ff` |
| Red (255,0,0) | `ff` | `0` | `0` |
| Green (0,128,0) | `0` | `80` | `0` |
| Bright Green (0,255,0) | `0` | `ff` | `0` |
| Blue (0,0,255) | `0` | `0` | `ff` |
| Yellow (255,255,0) | `ff` | `ff` | `0` |
| Orange (255,165,0) | `ff` | `a5` | `0` |
| Cyan (0,255,255) | `0` | `ff` | `ff` |
| Magenta (255,0,255) | `ff` | `0` | `ff` |
| Gray (128,128,128) | `80` | `80` | `80` |
| Dark Gray (64,64,64) | `40` | `40` | `40` |
| Light Gray (192,192,192) | `c0` | `c0` | `c0` |
| Navy (0,0,128) | `0` | `0` | `80` |
| Purple (128,0,128) | `80` | `0` | `80` |

**Formula:** `String.format("%x", decimalValue)` e.g. 128→`80`, 255→`ff`, 165→`a5`

### What causes errors
| Wrong format | Error |
|---|---|
| `red="255"` (decimal) | `IllegalArgumentException: Color parameter outside of expected range` |
| `red="0.0"` (float) | `NumberFormatException` |
| `alpha="255"` attribute | `IllegalArgumentException` on NetBeans 23+ |

---

## 11. Font Properties

```xml
<Property name="font" type="java.awt.Font" editor="org.netbeans.beaninfo.editors.FontEditor">
  <Font name="SansSerif" size="14" style="1"/>
</Property>
```

### style values
| Value | Meaning |
|-------|---------|
| `0` | Plain |
| `1` | Bold |
| `2` | Italic |
| `3` | Bold + Italic |

### Common font names
`SansSerif`, `Serif`, `Monospaced`, `Dialog`, `DialogInput`  
System fonts (`Arial`, `Tahoma`, etc.) work only if installed on the target machine.

Java equivalent: `component.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14)); // NOI18N`

---

## 12. Border Properties

```xml
<Property name="border" type="javax.swing.border.Border" editor="org.netbeans.modules.form.editors2.BorderEditor">
  <Border info="org.netbeans.modules.form.compat2.border.TitledBorderInfo">
    <TitledBorder title="Section Title"/>
  </Border>
</Property>
```

### Border type reference
| Border type | `info=` value | Inner element |
|-------------|--------------|---------------|
| TitledBorder | `...TitledBorderInfo` | `<TitledBorder title="..."/>` |
| EmptyBorder | `...EmptyBorderInfo` | `<EmptyBorder top="n" left="n" bottom="n" right="n"/>` |
| LineBorder | `...LineBorderInfo` | `<LineBorder/>` + color property |
| EtchedBorder | `...EtchedBorderInfo` | `<EtchetBorder/>` ⚠️ typo — see below |
| BevelBorder | `...BevelBorderInfo` | `<BevelBorder bevelType="0"/>` (0=RAISED, 1=LOWERED) |
| MatteBorder | `...MatteBorderInfo` | `<MatteBorder top="n" left="n" bottom="n" right="n"/>` |
| CompoundBorder | `...CompoundBorderInfo` | nested `<Border>` elements |

### ⚠️ EtchedBorder typo
`BorderEditor.readEtchedBorder()` looks for `"EtchetBorder"` (missing the 'd') — a **known typo in NetBeans source**.

**Wrong:** `<EtchedBorder/>` → `IOException: Invalid format: missing "EtchetBorder" element`  
**Correct:** `<EtchetBorder/>`

### EmptyBorder example
```xml
<Border info="org.netbeans.modules.form.compat2.border.EmptyBorderInfo">
  <EmptyBorder bottom="12" left="12" right="12" top="12"/>
</Border>
```

---

## 13. Dimension Property

```xml
<Property name="minimumSize" type="java.awt.Dimension" editor="org.netbeans.beaninfo.editors.DimensionEditor">
  <Dimension value="[297, 200]"/>
</Property>
```

Format: `[width, height]` with a space after the comma. Applies to `minimumSize`, `maximumSize`, `preferredSize`.

---

## 14. Component Reference (labelFor, buttonGroup)

```xml
<Property name="labelFor" type="java.awt.Component" editor="org.netbeans.modules.form.ComponentChooserEditor">
  <ComponentRef name="targetComponentName"/>
</Property>
```

Used for `labelFor` on `JLabel`, and for `buttonGroup` on radio buttons.

---

## 15. Component Properties Reference

### JLabel
```xml
<Component class="javax.swing.JLabel" name="myLabel">
  <Properties>
    <Property name="text"                type="java.lang.String" value="My Text"/>
    <Property name="horizontalAlignment" type="int"              value="0"/>
    <Property name="verticalAlignment"   type="int"              value="0"/>
    <Property name="font"   .../>
    <Property name="foreground" .../>
    <Property name="background" .../>
    <Property name="opaque" type="boolean" value="true"/>
  </Properties>
</Component>
```

### JButton
```xml
<Component class="javax.swing.JButton" name="myButton">
  <Properties>
    <Property name="text" type="java.lang.String" value="Click Me"/>
  </Properties>
  <Events>
    <EventHandler event="actionPerformed" listener="java.awt.event.ActionListener"
                  parameters="java.awt.event.ActionEvent" handler="myButtonActionPerformed"/>
  </Events>
</Component>
```

### JTextField
```xml
<Component class="javax.swing.JTextField" name="myTextField">
  <Properties>
    <Property name="text"    type="java.lang.String" value=""/>
    <Property name="columns" type="int"              value="20"/>
  </Properties>
</Component>
```

### JTextArea
```xml
<Component class="javax.swing.JTextArea" name="myTextArea">
  <Properties>
    <Property name="columns"       type="int"     value="20"/>
    <Property name="rows"          type="int"     value="5"/>
    <Property name="lineWrap"      type="boolean" value="true"/>
    <Property name="wrapStyleWord" type="boolean" value="true"/>
  </Properties>
</Component>
```

JTextArea is almost always wrapped in a JScrollPane (see Section 9).

### JPanel (container)
```xml
<Container class="javax.swing.JPanel" name="myPanel">
  <Properties>
    <Property name="border" .../>
    <Property name="background" .../>
  </Properties>
  <Layout>...</Layout>
  <SubComponents>...</SubComponents>
</Container>
```

### JCheckBox
```xml
<Component class="javax.swing.JCheckBox" name="myCheckBox">
  <Properties>
    <Property name="text"     type="java.lang.String" value="Enable feature"/>
    <Property name="selected" type="boolean"          value="false"/>
  </Properties>
</Component>
```

### JRadioButton + ButtonGroup
ButtonGroup must be declared in `NonVisualComponents` (it is non-visual):

```xml
<NonVisualComponents>
  <Component class="javax.swing.ButtonGroup" name="buttonGroup1">
  </Component>
</NonVisualComponents>
```

Then reference it in each radio button:
```xml
<Component class="javax.swing.JRadioButton" name="radioA">
  <Properties>
    <Property name="buttonGroup" type="javax.swing.ButtonGroup"
              editor="org.netbeans.modules.form.RADComponent$ButtonGroupPropertyEditor">
      <ComponentRef name="buttonGroup1"/>
    </Property>
    <Property name="text" type="java.lang.String" value="Option A"/>
    <Property name="selected" type="boolean" value="true"/>
  </Properties>
</Component>
```

Java GEN block: `buttonGroup1 = new javax.swing.ButtonGroup();`  
Variables block: `private javax.swing.ButtonGroup buttonGroup1;`

### JComboBox — ⚠️ CRITICAL: no model in .form

`org.netbeans.modules.form.editors2.JComboBoxModelEditor` does **not** exist in modern NetBeans — using `editor=` causes `ClassNotFoundException`. Omit the `model` property entirely from `.form` and set it in `setupComponents()`.

**❌ WRONG:**
```xml
<Property name="model" type="javax.swing.ComboBoxModel"
          editor="org.netbeans.modules.form.editors2.JComboBoxModelEditor">
  <StringArray count="3">...</StringArray>
</Property>
```

**✅ CORRECT — no model in .form:**
```xml
<Component class="javax.swing.JComboBox" name="myCombo">
</Component>
```

In `setupComponents()`:
```java
myCombo.setModel(new javax.swing.DefaultComboBoxModel<>(new String[]{"A", "B", "C"}));
```

Use raw type `class="javax.swing.JComboBox"` (no generics). Variables block: `private javax.swing.JComboBox myCombo;`

### JTable
```xml
<Component class="javax.swing.JTable" name="myTable">
  <Properties>
    <Property name="model" type="javax.swing.table.TableModel"
              editor="org.netbeans.modules.form.editors2.TableModelEditor">
      <Table columnCount="3" rowCount="4">
        <Column editable="true" title="Column 1" type="java.lang.Object"/>
        <Column editable="true" title="Column 2" type="java.lang.Object"/>
        <Column editable="true" title="Column 3" type="java.lang.Object"/>
      </Table>
    </Property>
    <Property name="autoResizeMode" type="int" value="4"/>
  </Properties>
</Component>
```

JTable is almost always wrapped in a `JScrollPane`.

---

## 16. JMenuBar — Full Pattern

JMenuBar lives in `NonVisualComponents` and is linked to the JFrame via `SyntheticProperty name="menuBar"`.

```xml
<NonVisualComponents>
  <Menu class="javax.swing.JMenuBar" name="jMenuBar1">
    <SubComponents>
      <Menu class="javax.swing.JMenu" name="fileMenu">
        <Properties>
          <Property name="text" type="java.lang.String" value="File"/>
          <Property name="mnemonic" type="int" value="70"/>
        </Properties>
        <SubComponents>
          <MenuItem class="javax.swing.JMenuItem" name="exitMenuItem">
            <Properties>
              <Property name="text" type="java.lang.String" value="Exit"/>
            </Properties>
            <Events>
              <EventHandler event="actionPerformed" listener="java.awt.event.ActionListener"
                            parameters="java.awt.event.ActionEvent" handler="exitMenuItemActionPerformed"/>
            </Events>
          </MenuItem>
        </SubComponents>
      </Menu>
    </SubComponents>
  </Menu>
</NonVisualComponents>
<SyntheticProperties>
  <SyntheticProperty name="menuBar" type="java.lang.String" value="jMenuBar1"/>
</SyntheticProperties>
```

### Menu element types
| XML element | Java class |
|-------------|-----------|
| `<Menu class="javax.swing.JMenuBar">` | `JMenuBar` (root) |
| `<Menu class="javax.swing.JMenu">` | `JMenu` |
| `<MenuItem class="javax.swing.JMenuItem">` | `JMenuItem` |
| `<MenuItem class="javax.swing.JCheckBoxMenuItem">` | `JCheckBoxMenuItem` |
| `<MenuItem class="javax.swing.JRadioButtonMenuItem">` | `JRadioButtonMenuItem` |
| `<MenuItem class="javax.swing.JSeparator">` | Separator |

Java GEN block:
```java
jMenuBar1 = new javax.swing.JMenuBar();
fileMenu  = new javax.swing.JMenu();
exitMenuItem = new javax.swing.JMenuItem();
// ...
setJMenuBar(jMenuBar1);
```

Variables block:
```java
private javax.swing.JMenu fileMenu;
private javax.swing.JMenuItem exitMenuItem;
private javax.swing.JMenuBar jMenuBar1;
```

---

## 17. Event Handling

### .form XML
```xml
<Events>
  <EventHandler event="actionPerformed"
                listener="java.awt.event.ActionListener"
                parameters="java.awt.event.ActionEvent"
                handler="myButtonActionPerformed"/>
</Events>
```

### Root-level events (JFrame)
```xml
<Events>
  <EventHandler event="windowClosing" listener="java.awt.event.WindowListener"
                parameters="java.awt.event.WindowEvent" handler="exitForm"/>
</Events>
```

### Common event signatures
| Component | event | listener | parameters |
|-----------|-------|----------|-----------|
| JButton | `actionPerformed` | `java.awt.event.ActionListener` | `java.awt.event.ActionEvent` |
| JTextField | `actionPerformed` | `java.awt.event.ActionListener` | `java.awt.event.ActionEvent` |
| JTextField | `keyReleased` | `java.awt.event.KeyListener` | `java.awt.event.KeyEvent` |
| JComboBox | `actionPerformed` | `java.awt.event.ActionListener` | `java.awt.event.ActionEvent` |
| JCheckBox | `actionPerformed` | `java.awt.event.ActionListener` | `java.awt.event.ActionEvent` |
| JList | `valueChanged` | `javax.swing.event.ListSelectionListener` | `javax.swing.event.ListSelectionEvent` |
| JFrame (root) | `windowClosing` | `java.awt.event.WindowListener` | `java.awt.event.WindowEvent` |

---

## 18. Property preCode / postCode

Properties can carry `preCode` and `postCode` attributes that wrap the generated setter call in arbitrary Java:

```xml
<Property name="enabled" type="boolean" value="false" preCode="if (false) {" postCode="}"/>
```

Generated Java: `if (false) { myComponent.setEnabled(false); }`

Used rarely, typically by NetBeans itself for design-time-only placeholders.

---

## 19. Alignment & Spacing Quick Reference

### horizontalAlignment (JLabel, JTextField, JButton)
| Value | Constant |
|-------|----------|
| `0` | CENTER |
| `2` | LEFT |
| `4` | RIGHT |
| `10` | LEADING |
| `11` | TRAILING |

### verticalAlignment (JLabel)
| Value | Constant |
|-------|----------|
| `0` | CENTER |
| `1` | TOP |
| `3` | BOTTOM |

### defaultCloseOperation (JFrame)
| Value | Constant |
|-------|----------|
| `0` | DO_NOTHING_ON_CLOSE |
| `1` | HIDE_ON_CLOSE |
| `2` | DISPOSE_ON_CLOSE |
| `3` | EXIT_ON_CLOSE ✅ |

---

## 20. Complete Working JFrame Template

### `views/MyFrame.form`
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<Form version="1.3" maxVersion="1.9" type="org.netbeans.modules.form.forminfo.JFrameFormInfo">
  <Properties>
    <Property name="defaultCloseOperation" type="int" value="3"/>
    <Property name="title" type="java.lang.String" value="My Application"/>
  </Properties>
  <SyntheticProperties>
    <SyntheticProperty name="formSizePolicy" type="int" value="1"/>
    <SyntheticProperty name="generateCenter" type="boolean" value="false"/>
  </SyntheticProperties>
  <AuxValues>
    <AuxValue name="FormSettings_autoResourcing"          type="java.lang.Integer" value="0"/>
    <AuxValue name="FormSettings_autoSetComponentName"    type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_generateFQN"             type="java.lang.Boolean" value="true"/>
    <AuxValue name="FormSettings_generateMnemonicsCode"   type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_i18nAutoMode"            type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_layoutCodeTarget"        type="java.lang.Integer" value="1"/>
    <AuxValue name="FormSettings_listenerGenerationStyle" type="java.lang.Integer" value="0"/>
    <AuxValue name="FormSettings_variablesLocal"          type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_variablesModifier"       type="java.lang.Integer" value="2"/>
  </AuxValues>
  <Layout>
    <DimensionLayout dim="0">
      <Group type="103" groupAlignment="0" attributes="0">
          <Component id="titleLabel" alignment="0" max="32767" attributes="0"/>
      </Group>
    </DimensionLayout>
    <DimensionLayout dim="1">
      <Group type="103" groupAlignment="0" attributes="0">
          <Component id="titleLabel" alignment="0" max="32767" attributes="0"/>
      </Group>
    </DimensionLayout>
  </Layout>
  <SubComponents>
    <Component class="javax.swing.JLabel" name="titleLabel">
      <Properties>
        <Property name="font" type="java.awt.Font" editor="org.netbeans.beaninfo.editors.FontEditor">
          <Font name="SansSerif" size="24" style="1"/>
        </Property>
        <Property name="foreground" type="java.awt.Color" editor="org.netbeans.beaninfo.editors.ColorEditor">
          <Color red="0" green="80" blue="0" type="rgb"/>
        </Property>
        <Property name="horizontalAlignment" type="int" value="0"/>
        <Property name="text" type="java.lang.String" value="Hello World"/>
      </Properties>
    </Component>
  </SubComponents>
</Form>
```

### `views/MyFrame.java`
```java
package com.example.views;

public class MyFrame extends javax.swing.JFrame {

    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(MyFrame.class.getName());

    public MyFrame() {
        initComponents();
    }

    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        titleLabel = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("My Application");

        titleLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 24)); // NOI18N
        titleLabel.setForeground(new java.awt.Color(0, 128, 0));
        titleLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        titleLabel.setText("Hello World");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(titleLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(titleLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 400, Short.MAX_VALUE)
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    public static void main(String args[]) {
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ex) {
            logger.log(java.util.logging.Level.SEVERE, null, ex);
        }
        java.awt.EventQueue.invokeLater(() -> new MyFrame().setVisible(true));
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel titleLabel;
    // End of variables declaration//GEN-END:variables
}
```

---

## 21. MCP Workflow Checklist

- [ ] Get project package from `pom.xml` → `<groupId>` + `<artifactId>`
- [ ] Create `ClassName.form` in `.../views/` package folder
- [ ] Create `ClassName.java` in `.../views/` package folder with correct `package` declaration
- [ ] `.form` component `id` names must match variable names in `GEN-BEGIN:variables`
- [ ] Use **hex** color values in `.form` (never decimal, never float)
- [ ] Use fully-qualified class names in `initComponents()` (`javax.swing.*`, `java.awt.*`)
- [ ] **Never** put `GEN-FIRST`/`GEN-LAST` inside anonymous listener bodies — use standalone methods
- [ ] **Never** declare `GridBagConstraints`, custom model/table objects, or inner class instances inside `GEN block` — all go in `setupComponents()`
- [ ] **Never** include `model` property for `JComboBox` in `.form` — set via `setupComponents()`
- [ ] `JComboBox` in `.form`: raw type `class="javax.swing.JComboBox"` (no generics); same in GEN variables block
- [ ] `ButtonGroup` declared in `<NonVisualComponents>` (non-visual), referenced via `<ComponentRef>`
- [ ] `JMenuBar` declared in `<NonVisualComponents>` + linked via `<SyntheticProperty name="menuBar" value="..."/>`
- [ ] `JScrollPane` children use `JScrollPaneSupportLayout` + `autoScrollPane` AuxValue
- [ ] `JTabbedPane` children use `JTabbedPaneSupportLayout` + `JTabbedPaneConstraints` with `tabTitle`
- [ ] `EtchedBorder` uses `<EtchetBorder/>` (typo in NetBeans source — intentional)
- [ ] Call `mcp0_openFile` (NetBeans MCP tool) to open `.java` in NetBeans after creation
- [ ] User closes and reopens the tab in NetBeans to load Design view fresh

---

## 22. Useful NetBeans MCP Tools

| Tool | When to use |
|------|-------------|
| `mcp0_getWorkspaceFolders` | Get open projects + paths |
| `mcp0_getOpenEditors` | See what's currently open |
| `mcp0_openFile` | Open a file in NetBeans editor |
| `mcp0_getDiagnostics` | Check for compile errors |
| `mcp0_saveDocument` | Save a file |
| `mcp0_getCurrentSelection` | Read selected text |
| `mcp0_show_markdown` | Show plan/summary in NetBeans |
| `mcp0_permission_prompt` | Show diff and ask Accept/Deny |
