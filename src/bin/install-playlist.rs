fn main() {
    let args: Vec<_> = std::env::args_os().collect();
    if args.len() != 3 {
        eprintln!("Usage: install-playlist <service-data-directory> <playlist.json>");
        std::process::exit(2);
    }
    match noqui_web::library::install(
        std::path::Path::new(&args[1]),
        std::path::Path::new(&args[2]),
    ) {
        Ok(()) => println!("Playlist installed. Restart the audio service to load it."),
        Err(error) => {
            eprintln!("Playlist installation failed: {error}");
            std::process::exit(1);
        }
    }
}
