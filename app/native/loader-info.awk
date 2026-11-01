# Al posto di loader/loader-info.awk di proot (app/native/proot.sh). Scrive loader/loader-info.c, solo per arm64:
# quanti byte dopo _start sta pokedata_workaround nel caricatore (proot lo usa se PTRACE_POKEDATA non va).
# Fa lo stesso conto e scrive lo stesso file, ma va con ogni awk (quello di proot vuole strtonum e \y di gawk)
# e si ferma se non trova i due simboli: senza, l'offset sarebbe 0 e proot sbaglierebbe senza dirlo.
# Legge l'uscita di "readelf -s": il valore, in esadecimale, è il secondo campo; il nome è l'ultimo.
function hex(s,    i, n) {
	n = 0
	s = tolower(s)
	for (i = 1; i <= length(s); i++)
		n = n * 16 + index("0123456789abcdef", substr(s, i, 1)) - 1
	return n
}
$NF == "pokedata_workaround" { pokedata_workaround = hex($2); has_workaround = 1 }
$NF == "_start" { start = hex($2); has_start = 1 }
END {
	if (!has_workaround || !has_start) {
		print "loader-info.awk: pokedata_workaround o _start non sono tra i simboli del caricatore" > "/dev/stderr"
		exit 1
	}
	print "#include <unistd.h>"
	printf "const ssize_t offset_to_pokedata_workaround=%d;\n", pokedata_workaround - start
}
