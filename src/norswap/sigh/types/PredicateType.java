package norswap.sigh.types;

import norswap.utils.NArrays;
import java.util.Arrays;

public final class PredicateType extends Type
{
    public final Type[] paramTypes;

    public PredicateType (Type... paramTypes) {
        this.paramTypes = paramTypes;
    }

    @Override public String name() {
        String[] params = NArrays.map(paramTypes, new String[0], Type::name);
        return String.format("(%s) -> %s", String.join(",", params));
    }

    @Override public boolean equals (Object o) {
        if (this == o) return true;
        if (!(o instanceof FunType)) return false;
        FunType other = (FunType) o;

        return returnType.equals(other.returnType)
            && Arrays.equals(paramTypes, other.paramTypes);
    }

    @Override public int hashCode () {
        return 31 * returnType.hashCode() + Arrays.hashCode(paramTypes);
    }
}

