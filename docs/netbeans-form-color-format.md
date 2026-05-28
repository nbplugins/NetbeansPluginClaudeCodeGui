---
name: netbeans
description: "Learned from hands-on experience + Apache NetBeans official tutorials. Use this file whenever creating NetBeans GUI forms via MCP/Windsurf."
---

## 1. File Creation Rules

### Always create TWO files per JFrame
| File | Purpose |
|------|---------|
| `ClassName.java` | Java source with `GEN-BEGIN/END` markers |
| `ClassName.form` | XML design descriptor (Matisse format) |

### Package / folder rule
- Files must be in the **`views`** package (`.../views/ClassName.java`) for the `.form` to appear in the NetBeans Projects panel
- **Maven projects always show `.form` as a visible separate node** regardless of which package the files are in — this is normal, unavoidable NetBeans/Maven behaviour; it is cosmetic clutter only and does not affect functionality
- Ant-based projects always hide `.form` nodes — that is normal NetBeans behaviour

### Mandatory `.java` structure
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
        // ... all component setup here
        pack();
    }// </editor-fold>//GEN-END:initComponents

    public static void main(String args[]) {
        // look and feel + EventQueue.invokeLater
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel myLabel;
    // End of variables declaration//GEN-END:variables
}
```

**Critical markers** — without these the Design tab will NOT appear:
- `//GEN-BEGIN:initComponents` … `//GEN-END:initComponents`
- `//GEN-BEGIN:variables` … `//GEN-END:variables`
- All types must be fully qualified (`javax.swing.JLabel`, not `JLabel`)

---

## 2. .form XML Structure

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<Form version="1.3" maxVersion="1.9" type="org.netbeans.modules.form.forminfo.JFrameFormInfo">
  <Properties>
    <Property name="defaultCloseOperation" type="int" value="3"/>
    <Property name="title" type="java.lang.String" value="My Title"/>
  </Properties>
  <SyntheticProperties>
    <SyntheticProperty name="formSizePolicy" type="int" value="1"/>
    <SyntheticProperty name="generateCenter" type="boolean" value="false"/>
  </SyntheticProperties>
  <AuxValues>
    <AuxValue name="FormSettings_autoResourcing" type="java.lang.Integer" value="0"/>
    <AuxValue name="FormSettings_autoSetComponentName" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_generateFQN" type="java.lang.Boolean" value="true"/>
    <AuxValue name="FormSettings_generateMnemonicsCode" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_i18nAutoMode" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_layoutCodeTarget" type="java.lang.Integer" value="1"/>
    <AuxValue name="FormSettings_listenerGenerationStyle" type="java.lang.Integer" value="3"/>
    <AuxValue name="FormSettings_variablesLocal" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_variablesModifier" type="java.lang.Integer" value="2"/>
  </AuxValues>
  <Layout> ... </Layout>
  <SubComponents> ... </SubComponents>
</Form>
```

### defaultCloseOperation values
| Value | Constant |
|-------|----------|
| `0` | DO_NOTHING_ON_CLOSE |
| `1` | HIDE_ON_CLOSE |
| `2` | DISPOSE_ON_CLOSE |
| `3` | EXIT_ON_CLOSE ✅ (use this) |

---

## 3. Layout — GroupLayout (default)

### Full-frame single component (fills entire window)
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

### Matching Java code in initComponents()
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

### Empty frame (no components yet)
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

---

## 4. Color Properties — ⚠️ CRITICAL

`ColorEditor.readFromXML()` calls `Integer.parseInt(value, 16)` — values are **hex strings**.

### Correct format
```xml
<Property name="foreground" type="java.awt.Color" editor="org.netbeans.beaninfo.editors.ColorEditor">
  <Color red="HH" green="HH" blue="HH" type="rgb"/>
</Property>
```

Same format applies to `background`, `foreground`, `caretColor`, `selectionColor`, etc.

### Color table (decimal → hex)

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
| Brown (139,69,19) | `8b` | `45` | `13` |

**Formula:** decimal → hex: `String.format("%x", value)` e.g. 128→`80`, 255→`ff`, 165→`a5`

### What causes errors

| Wrong format | Error |
|---|---|
| `red="255"` (decimal) | `IllegalArgumentException: Color parameter outside of expected range` |
| `red="0.0"` (float) | `NumberFormatException: For input string: "0.0" under radix 16` |
| `alpha="255"` attribute | `IllegalArgumentException` on NetBeans 23+ |

---

## 5. Font Properties

```xml
<Property name="font" type="java.awt.Font" editor="org.netbeans.beaninfo.editors.FontEditor">
  <Font name="SansSerif" size="14" style="1"/>
</Property>
```

### style values
| Value | Meaning |
|-------|---------|
| `0` | Plain |
| `1` | **Bold** |
| `2` | *Italic* |
| `3` | ***Bold + Italic*** |

### Common font names
- `SansSerif` — default clean sans-serif
- `Serif` — serif font
- `Monospaced` — fixed-width (code)
- `Dialog` — system UI font
- `Arial`, `Tahoma`, `Verdana` — if installed on target system

### Java code equivalent
```java
component.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14)); // NOI18N
```

---

## 6. Component Properties Reference

### JLabel
```xml
<Component class="javax.swing.JLabel" name="myLabel">
  <Properties>
    <Property name="font" .../>
    <Property name="foreground" .../>
    <Property name="background" .../>
    <Property name="horizontalAlignment" type="int" value="0"/>
    <Property name="verticalAlignment" type="int" value="0"/>
    <Property name="text" type="java.lang.String" value="My Text"/>
    <Property name="opaque" type="boolean" value="true"/>
  </Properties>
</Component>
```

### JButton
```xml
<Component class="javax.swing.JButton" name="myButton">
  <Properties>
    <Property name="text" type="java.lang.String" value="Click Me"/>
    <Property name="font" .../>
    <Property name="background" .../>
    <Property name="foreground" .../>
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
    <Property name="text" type="java.lang.String" value=""/>
    <Property name="font" .../>
    <Property name="columns" type="int" value="20"/>
  </Properties>
</Component>
```

### JTextArea
```xml
<Component class="javax.swing.JTextArea" name="myTextArea">
  <Properties>
    <Property name="columns" type="int" value="20"/>
    <Property name="rows" type="int" value="5"/>
    <Property name="text" type="java.lang.String" value=""/>
    <Property name="lineWrap" type="boolean" value="true"/>
    <Property name="wrapStyleWord" type="boolean" value="true"/>
  </Properties>
</Component>
```

### JPanel
```xml
<Container class="javax.swing.JPanel" name="myPanel">
  <Properties>
    <Property name="background" .../>
    <Property name="border" type="javax.swing.border.Border"
              editor="org.netbeans.modules.form.editors2.BorderEditor">
      <Border info="org.netbeans.modules.form.compat2.border.TitledBorderInfo">
        <TitledBorder title="Section Title"/>
      </Border>
    </Property>
  </Properties>
  <Layout>...</Layout>
  <SubComponents>...</SubComponents>
</Container>
```

### EtchedBorder — ⚠️ TYPO IN NETBEANS SOURCE

`BorderEditor.readEtchedBorder()` looks for the element `"EtchetBorder"` (missing the 'd') — this is a **known typo in NetBeans source code**.

**Wrong** (causes `IOException: Invalid format: missing "EtchetBorder" element`):
```xml
<Border info="org.netbeans.modules.form.compat2.border.EtchedBorderInfo">
  <EtchedBorder/>
</Border>
```

**Correct** (use the typo'd name):
```xml
<Border info="org.netbeans.modules.form.compat2.border.EtchedBorderInfo">
  <EtchetBorder/>
</Border>
```

### JComboBox
```xml
<Component class="javax.swing.JComboBox" name="myCombo">
  <Properties>
    <Property name="model" type="javax.swing.ComboBoxModel"
              editor="org.netbeans.modules.form.editors2.JComboBoxModelEditor">
      <StringArray count="3">
        <StringItem index="0" value="Option 1"/>
        <StringItem index="1" value="Option 2"/>
        <StringItem index="2" value="Option 3"/>
      </StringArray>
    </Property>
  </Properties>
</Component>
```

### JCheckBox
```xml
<Component class="javax.swing.JCheckBox" name="myCheckBox">
  <Properties>
    <Property name="text" type="java.lang.String" value="Enable feature"/>
    <Property name="selected" type="boolean" value="false"/>
  </Properties>
</Component>
```

### JRadioButton
```xml
<Component class="javax.swing.JRadioButton" name="myRadio">
  <Properties>
    <Property name="text" type="java.lang.String" value="Option A"/>
    <Property name="selected" type="boolean" value="true"/>
    <Property name="buttonGroup" type="javax.swing.ButtonGroup"
              editor="org.netbeans.modules.form.RADComponent$ButtonGroupPropertyEditor">
      <ComponentRef name="buttonGroup1"/>
    </Property>
  </Properties>
</Component>
```

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

---

## 7. Alignment & Spacing Values

### horizontalAlignment (JLabel, JTextField)
| Value | Constant |
|-------|----------|
| `0` | CENTER |
| `2` | LEFT (LEADING) |
| `4` | RIGHT (TRAILING) |
| `10` | LEADING |
| `11` | TRAILING |

### verticalAlignment (JLabel)
| Value | Constant |
|-------|----------|
| `0` | CENTER |
| `1` | TOP |
| `3` | BOTTOM |

---

## 8. Event Handling in .form + .java

### .form XML — declare the event
```xml
<Events>
  <EventHandler event="actionPerformed"
                listener="java.awt.event.ActionListener"
                parameters="java.awt.event.ActionEvent"
                handler="myButtonActionPerformed"/>
</Events>
```

### .java — implement the handler (outside GEN blocks)
```java
private void myButtonActionPerformed(java.awt.event.ActionEvent evt) {
    // your code here
}
```

### Common events
| Component | Event | Handler signature |
|-----------|-------|-------------------|
| JButton | `actionPerformed` | `ActionEvent evt` |
| JTextField | `actionPerformed` | `ActionEvent evt` |
| JTextField | `keyReleased` | `KeyEvent evt` |
| JComboBox | `actionPerformed` | `ActionEvent evt` |
| JCheckBox | `actionPerformed` | `ActionEvent evt` |
| JFrame | `windowClosing` | `WindowEvent evt` |
| JFrame | `formWindowOpened` | `WindowEvent evt` |

---

## 9. Complete Working JFrame Template

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
    <AuxValue name="FormSettings_autoResourcing" type="java.lang.Integer" value="0"/>
    <AuxValue name="FormSettings_autoSetComponentName" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_generateFQN" type="java.lang.Boolean" value="true"/>
    <AuxValue name="FormSettings_generateMnemonicsCode" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_i18nAutoMode" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_layoutCodeTarget" type="java.lang.Integer" value="1"/>
    <AuxValue name="FormSettings_listenerGenerationStyle" type="java.lang.Integer" value="3"/>
    <AuxValue name="FormSettings_variablesLocal" type="java.lang.Boolean" value="false"/>
    <AuxValue name="FormSettings_variablesModifier" type="java.lang.Integer" value="2"/>
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
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
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
        //</editor-fold>
        java.awt.EventQueue.invokeLater(() -> new MyFrame().setVisible(true));
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel titleLabel;
    // End of variables declaration//GEN-END:variables
}
```

---

## 10. MCP Workflow Checklist

When creating a new JFrame via MCP (Windsurf → NetBeans):

- [ ] Get project package from `pom.xml` → `<groupId>` + `<artifactId>`
- [ ] Create `ClassName.form` in `.../views/` package folder
- [ ] Create `ClassName.java` in `.../views/` package folder with correct package declaration
- [ ] Ensure `.form` component `id` names match variable names in `.java` `GEN-BEGIN:variables`
- [ ] Use **hex** color values in `.form` (never decimal, never float)
- [ ] Use fully-qualified class names in `initComponents()` (`javax.swing.*`, `java.awt.*`)
- [ ] Call `mcp0_openFile` to open the `.java` in NetBeans after creation
- [ ] User closes and reopens the tab in NetBeans to load Design view fresh

---

## 11. Useful NetBeans MCP Tools

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

