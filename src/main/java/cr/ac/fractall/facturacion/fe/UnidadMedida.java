package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Unidades de medida según XSD FE v4.4 (UnidadMedidaType, basado en RTC 443:2010). 101 valores.
@Getter
public enum UnidadMedida {

    // 1 — uno (índice de refracción)
    UNO_REFRACCION("1", "Uno (índice de refracción)"),
    // ´ — minuto (angular)
    MINUTO_ANGULAR("´", "Minuto (angular)"),
    // ´´ — segundo (angular)
    SEGUNDO_ANGULAR("´´", "Segundo (angular)"),
    // °C — grado Celsius
    GRADO_CELSIUS("°C", "Grado Celsius"),
    // 1/m — 1 por metro
    UNO_POR_METRO("1/m", "1 por metro"),
    // A — Ampere
    AMPERE("A", "Ampere"),
    // A/m — ampere por metro
    AMPERE_POR_METRO("A/m", "Ampere por metro"),
    // A/m² — ampere por metro cuadrado
    AMPERE_POR_M2("A/m²", "Ampere por metro cuadrado"),
    // Acv — activo virtual
    ACV("Acv", "Activo virtual"),
    // Al — alquiler de uso habitacional
    AL("Al", "Alquiler de uso habitacional"),
    // Alc — alquiler de uso comercial
    ALC("Alc", "Alquiler de uso comercial"),
    // B — bel
    BEL("B", "Bel"),
    // Bq — Becquerel
    BQ("Bq", "Becquerel"),
    // C — coulomb
    COULOMB("C", "Coulomb"),
    // C/kg — coulomb por kilogramo
    COULOMB_POR_KG("C/kg", "Coulomb por kilogramo"),
    // C/m² — coulomb por metro cuadrado
    COULOMB_POR_M2("C/m²", "Coulomb por metro cuadrado"),
    // C/m³ — coulomb por metro cúbico
    COULOMB_POR_M3("C/m³", "Coulomb por metro cúbico"),
    // Cc — cajuela de café
    CC("Cc", "Cajuela de café"),
    // Cd — candela
    CANDELA("Cd", "Candela"),
    // cd/m² — candela por metro cuadrado
    CANDELA_POR_M2("cd/m²", "Candela por metro cuadrado"),
    // Cm — comisiones
    CM("Cm", "Comisiones"),
    // cm — centímetro
    CENTIMETRO("cm", "Centímetro"),
    // Cu — cuartillos de café
    CU("Cu", "Cuartillos de café"),
    // D — día
    D("D", "Día"),
    // eV — electronvolt
    ELECTRONVOLT("eV", "Electronvolt"),
    // F — farad
    FARAD("F", "Farad"),
    // F/m — farad por metro
    FARAD_POR_METRO("F/m", "Farad por metro"),
    // Fa — fanega de café
    FA("Fa", "Fanega de café"),
    // G — gramo
    G("G", "Gramo"),
    // Gal — galón
    GAL("Gal", "Galón"),
    // Gy — gray
    GY("Gy", "Gray"),
    // Gy/s — gray por segundo
    GY_POR_SEGUNDO("Gy/s", "Gray por segundo"),
    // h — hora
    HORA("h", "Hora"),
    // H — henry
    HENRY("H", "Henry"),
    // H/m — henry por metro
    HENRY_POR_METRO("H/m", "Henry por metro"),
    // Hz — hertz
    HZ("Hz", "Hertz"),
    // I — intereses
    I("I", "Intereses"),
    // J — joule
    J("J", "Joule"),
    // J/(kg·K) — joule por kilogramo kelvin
    JOULE_POR_KG_KELVIN("J/(kg·K)", "Joule por kilogramo kelvin"),
    // J/(mol·K) — joule por mol kelvin
    JOULE_POR_MOL_KELVIN("J/(mol·K)", "Joule por mol kelvin"),
    // J/K — joule por kelvin
    JOULE_POR_KELVIN("J/K", "Joule por kelvin"),
    // J/kg — joule por kilogramo
    JOULE_POR_KG("J/kg", "Joule por kilogramo"),
    // J/m³ — joule por metro cúbico
    JOULE_POR_M3("J/m³", "Joule por metro cúbico"),
    // J/mol — joule por mol
    JOULE_POR_MOL("J/mol", "Joule por mol"),
    // K — kelvin
    K("K", "Kelvin"),
    // Kat — katal
    KAT("Kat", "Katal"),
    // kat/m³ — katal por metro cúbico
    KATAL_POR_M3("kat/m³", "Katal por metro cúbico"),
    // Kg — kilogramo
    KG("Kg", "Kilogramo"),
    // kg/m³ — kilogramo por metro cúbico
    KG_POR_M3("kg/m³", "Kilogramo por metro cúbico"),
    // Km — kilómetro
    KM("Km", "Kilómetro"),
    // Kw — kilovatios
    KW("Kw", "Kilovatios"),
    // kWh — kilovatios por hora
    KWH("kWh", "Kilovatios por hora"),
    // L — litro
    L("L", "Litro"),
    // Lm — lumen
    LM("Lm", "Lumen"),
    // Ln — pulgada
    LN("Ln", "Pulgada"),
    // Lx — lux
    LX("Lx", "Lux"),
    // M — metro
    M("M", "Metro"),
    // m/s — metro por segundo
    METRO_POR_SEGUNDO("m/s", "Metro por segundo"),
    // m/s² — metro por segundo cuadrado
    METRO_POR_SEGUNDO_CUADRADO("m/s²", "Metro por segundo cuadrado"),
    // m² — metro cuadrado
    M2("m²", "Metro cuadrado"),
    // m³ — metro cúbico
    M3("m³", "Metro cúbico"),
    // Min — minuto
    MIN("Min", "Minuto"),
    // mL — mililitro
    ML("mL", "Mililitro"),
    // Mm — milímetro
    MM("Mm", "Milímetro"),
    // Mol — mol
    MOL("Mol", "Mol"),
    // mol/m³ — mol por metro cúbico
    MOL_POR_M3("mol/m³", "Mol por metro cúbico"),
    // N — newton
    N("N", "Newton"),
    // N/m — newton por metro
    NEWTON_POR_METRO("N/m", "Newton por metro"),
    // N·m — newton metro
    NEWTON_METRO("N·m", "Newton metro"),
    // Np — neper
    NP("Np", "Neper"),
    // º — grado (angular)
    GRADO_ANGULAR("º", "Grado (angular)"),
    // Os — otro tipo de servicio
    OS("Os", "Otro tipo de servicio"),
    // Otros — otros (indicar descripción)
    OTROS("Otros", "Otros (indicar descripción)"),
    // Oz — onzas
    OZ("Oz", "Onzas"),
    // Pa — pascal
    PA("Pa", "Pascal"),
    // Pa·s — pascal segundo
    PASCAL_SEGUNDO("Pa·s", "Pascal segundo"),
    // Qq — quintal
    QQ("Qq", "Quintal"),
    // Rad — radián
    RAD("Rad", "Radián"),
    // rad/s — radián por segundo
    RADIAN_POR_SEGUNDO("rad/s", "Radián por segundo"),
    // rad/s² — radián por segundo cuadrado
    RADIAN_POR_SEGUNDO_CUADRADO("rad/s²", "Radián por segundo cuadrado"),
    // S — segundo (unidad de tiempo)
    S("S", "Segundo (unidad de tiempo)"),
    // s — siemens
    SIEMENS("s", "Siemens"),
    // Sp — servicios profesionales
    SP("Sp", "Servicios profesionales"),
    // Spe — servicios personales
    SPE("Spe", "Servicios personales"),
    // Sr — estereorradián
    SR("Sr", "Estereorradián"),
    // St — servicios técnicos
    ST("St", "Servicios técnicos"),
    // Sv — sievert
    SV("Sv", "Sievert"),
    // t — tesla
    TESLA("t", "Tesla"),
    // T — tonelada
    T("T", "Tonelada"),
    // U — unidad de masa atómica unificada
    U("U", "Unidad de masa atómica unificada"),
    // Ua — unidad astronómica
    UA("Ua", "Unidad astronómica"),
    // Unid — unidad
    UNID("Unid", "Unidad"),
    // V — volt
    V("V", "Volt"),
    // V/m — volt por metro
    VOLT_POR_METRO("V/m", "Volt por metro"),
    // W — watt
    W("W", "Watt"),
    // W/(m·K) — watt por metro kelvin
    WATT_POR_METRO_KELVIN("W/(m·K)", "Watt por metro kelvin"),
    // W/(m²·sr) — watt por metro cuadrado estereorradián
    WATT_POR_M2_SR("W/(m²·sr)", "Watt por metro cuadrado estereorradián"),
    // W/m² — watt por metro cuadrado
    WATT_POR_M2("W/m²", "Watt por metro cuadrado"),
    // W/sr — watt por estereorradián
    WATT_POR_SR("W/sr", "Watt por estereorradián"),
    // Wb — weber
    WB("Wb", "Weber"),
    // Ω — ohm
    OHM("Ω", "Ohm");

    private final String codigo;
    private final String descripcion;

    UnidadMedida(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static UnidadMedida fromCodigo(String codigo) {
        for (UnidadMedida item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de unidad de medida inválido: " + codigo);
    }
}
