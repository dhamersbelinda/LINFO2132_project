package norswap.sigh.types;

public final class AtomType extends Type
{
    public static final AtomType INSTANCE = new AtomType();
    private AtomType() {}

    @Override public boolean isPrimitive () {
        return true;
    }

    @Override public String name() {
        return "Atom";
    }
}
