package org.jd.gui.service.project;

import com.google.common.base.Strings;
import org.objectweb.asm.Type;

import java.util.Objects;

/**
 * @author Yun Shan
 */
public class JavaIdentifier {

    private final String fullName;
    private final String friendlyFullName;
    private String friendlyDisplay;
    private boolean isInitMethod = false;
    private final String[] fullNameArray = new String[3];

    private String alias;
    private String comment;

    static String makeFullName(String internalClassName) {
        return makeFullName(internalClassName, null, null);
    }

    static String makeFullName(String internalClassName, String memberName, String memberDescriptor) {
        if (memberName == null || memberDescriptor == null) {
            return internalClassName;
        } else {
            return internalClassName + "#" + memberName + "#" + memberDescriptor;
        }
    }

    public JavaIdentifier(String internalClassName) {
        this(internalClassName, null, null);
    }

    public JavaIdentifier(String internalClassName, String memberName, String memberDescriptor) {
        Objects.requireNonNull(internalClassName);
        this.fullNameArray[0] = internalClassName;
        this.fullNameArray[1] = memberName;
        this.fullNameArray[2] = memberDescriptor;
        if (Strings.isNullOrEmpty(memberName) || Strings.isNullOrEmpty(memberDescriptor)) {
            this.fullName = internalClassName;
            this.friendlyFullName = "TypeName: " + internalClassName;
        } else {
            this.fullName = makeFullName(
                Objects.requireNonNull(internalClassName),
                Objects.requireNonNull(memberName),
                Objects.requireNonNull(memberDescriptor));
            this.friendlyFullName = "TypeName: " + internalClassName + "\nName: " + memberName + "\nDescriptor: " + memberDescriptor;
            if (Type.getType(memberDescriptor).getSort() == Type.METHOD) {
                if ("<init>".equals(memberName) || "<clinit>".equals(memberName)) {
                    this.isInitMethod = true;
                }
            }
        }
        this.updateFriendlyDisplay();
    }

    private void updateFriendlyDisplay() {
        if (this.hasComment()) {
            this.friendlyDisplay = this.friendlyFullName + "\nComment: " + this.comment;
        } else {
            this.friendlyDisplay = this.friendlyFullName;
        }
    }

    public boolean hasAlias() {
        return this.alias != null;
    }

    public String getAlias() {
        return this.canSetAlias() ? this.alias : null;
    }

    /**
     * type:
     * <p>
     * class: internalTypeName<br>
     * method: internalTypeName + "#" + methodName + "#" + methodDescriptor<br>
     * field: internalTypeName + "#" + fieldName + "#" + fieldDescriptor<br>
     *
     * @return full name
     */
    public String getFullName() {
        return this.fullName;
    }

    /**
     * set alias for this identifier
     * <p>
     * if {@link #canSetAlias()} return false, this method will do nothing
     *
     * @param newAlias new alias for this identifier
     * @see #canSetAlias()
     */
    void setAlias(String newAlias) {
        if (this.canSetAlias()) {
            this.alias = Strings.emptyToNull(newAlias);
        }
    }

    /**
     * get if this identifier can set alias
     * <p>
     * only a declaration identifier and it is not an &lt;init&gt;(constructor) and &lt;clinit&gt;(class initializer) method can set alias
     *
     * @return true if this identifier can set alias
     */
    public boolean canSetAlias() {
        return !this.isInitMethod;
    }

    public String getFriendlyDisplay() {
        return this.friendlyDisplay;
    }

    public String[] getFullNameArray() {
        return this.fullNameArray;
    }

    public String getComment() {
        return this.comment;
    }

    public void setComment(String comment) {
        this.comment = Strings.emptyToNull(comment);
        this.updateFriendlyDisplay();
    }

    public boolean hasComment() {
        return this.comment != null;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JavaIdentifier that = (JavaIdentifier) o;
        return this.fullName.equals(that.fullName);
    }

    @Override
    public final int hashCode() {
        return this.fullName.hashCode();
    }
}
