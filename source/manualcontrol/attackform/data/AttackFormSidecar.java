package manualcontrol.attackform.data;

import common.io.json.JsonClass;

@JsonClass(noTag = JsonClass.NoTag.LOAD)
public final class AttackFormSidecar {

    public String feature = "switch_attack_form";
    public String packId = "";
    public Entry[] entities = new Entry[0];

    public AttackFormSidecar() {}

    @JsonClass(noTag = JsonClass.NoTag.LOAD)
    public static final class Entry {
        public String kind = "";
        public int entityId = -1;
        public int formId = -1;
        public String animationPack = "";
        public String animationId = "";
        public String animationBase = "";
        public AttackFormSet data;

        public Entry() {}

        public String key() {
            return "unit".equals(kind)
                    ? "unit/" + entityId + "/form/" + formId
                    : "enemy/" + entityId;
        }
    }
}
